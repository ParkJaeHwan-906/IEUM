package com.hwannee.ieum.orders.reconcile;

import com.hwannee.ieum.orders.domain.OrderState;
import com.hwannee.ieum.orders.repository.UsersOrdersRepository;
import com.hwannee.ieum.orders.stock.StockKeys;
import com.hwannee.ieum.stores.domain.StoresItems;
import com.hwannee.ieum.stores.repository.StoresItemsRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;

// redis 전략의 원장 stock:{itemId} 를 DB 주문 상태로 다시 계산해 대조한다. 재고의 진실은 Redis 값이 아니라 DB 주문 상태다 (ADR-0003).
//   기대값 = initial − 활성 주문 수량 − 픽업 완료 수량. 불변식 initial = remaining + active + pickedUp 의 remaining 을 푼 것
// 지금은 탐지만 한다. 어긋난 건수를 stock.reconciliation{outcome=mismatch} 로 올리고 WARN 을 남긴다.
// TODO(2.2 Reconciliation 정정): 어긋나면 SET stock:{id} 기대값. 단, 대조 사이에 들어온 예약(Lua 차감) 과 경합하므로
//   "DB 스냅샷 시각 이후의 차감" 을 어떻게 셀지 정한 뒤 켠다. 후보: 상품별 짧은 정지 플래그 / 기대값과 현재값의 차이가 임계 이상일 때만
// TODO(2.2 DB 투영 갱신): stores_items.remaining_quantity = 기대값 으로 UPDATE. 워밍업(SET NX) 이 이 열에서 값을 가져오므로 투영이 최신이어야 재기동이 안전
// TODO(2.2 만료 누락): findReadyForPickupBefore(now − PICKUP_TTL) 로 인덱스에서 빠진 만료 후보를 찾아 ExpiryIndex 에 다시 등록
// TODO(4 관측): mismatch 건수를 README Reservation Correctness 대시보드의 "재고 불일치" 로. 전체 상품 순회는 페이징 필요
@Component
@ConditionalOnProperty(name = "ieum.stock.strategy", havingValue = "redis")
public class StockReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(StockReconciliationJob.class);

    private final StoresItemsRepository items;
    private final UsersOrdersRepository orders;
    private final StringRedisTemplate redis;
    private final Counter checked;
    private final Counter mismatch;
    private final Counter missing;

    public StockReconciliationJob(StoresItemsRepository items, UsersOrdersRepository orders,
                                  StringRedisTemplate redis, MeterRegistry registry) {
        this.items = items;
        this.orders = orders;
        this.redis = redis;
        this.checked = outcome(registry, "checked");
        this.mismatch = outcome(registry, "mismatch");
        this.missing = outcome(registry, "missing");
    }

    @Scheduled(fixedDelayString = "${ieum.order.reconciliation.interval:PT60S}")
    @Transactional(readOnly = true)
    public void run() {
        for (StoresItems item : items.findAll()) {
            check(item);
        }
    }

    void check(StoresItems item) {
        long reserved = orders.sumQuantityByItemAndStates(item.getId(), OrderState.ACTIVE)
                + orders.sumQuantityByItemAndStates(item.getId(), EnumSet.of(OrderState.PICKED_UP));
        long expected = item.getInitialQuantity() - reserved;
        String actual = redis.opsForValue().get(StockKeys.stock(item.getId()));
        checked.increment();
        if (actual == null) {
            missing.increment();
            return;
        }
        if (Long.parseLong(actual) != expected) {
            mismatch.increment();
            log.warn("재고 불일치: itemId={} expected={} redis={} dbProjection={}",
                    item.getId(), expected, actual, item.getRemainingQuantity());
        }
    }

    private static Counter outcome(MeterRegistry registry, String outcome) {
        return Counter.builder("stock.reconciliation").tag("outcome", outcome).register(registry);
    }
}
