package com.hwannee.ieum.orders.expiry;

import com.hwannee.ieum.orders.domain.InvalidOrderStateException;
import com.hwannee.ieum.orders.exception.OrderException;
import com.hwannee.ieum.orders.service.OrderService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

// READY_FOR_PICKUP 진입 후 PICKUP_TTL 이 지난 주문을 EXPIRED 로 전이하고 재고를 복구한다.
// TODO(2.2 복구 멱등성): 같은 id 가 두 번 처리되면(워커 중복 실행, 크래시 후 재시도) restore 가 두 번 나간다.
//   expire 전이는 상태 가드로 1회지만 restore 는 아니다. restore 시그니처에 orderId 를 더하고 restored:{orderId} SETNX 로 막는다
// TODO(2.2 Reconciliation): 인덱스에 없는데 DB 는 READY_FOR_PICKUP 이고 readyAt 이 지난 주문은 여기서 못 본다. findReadyForPickupBefore 로 잡는 것은 Reconciliation 의 몫
@Component
public class ExpiryWorker {

    private static final Logger log = LoggerFactory.getLogger(ExpiryWorker.class);
    private static final int BATCH = 100;

    private final ExpiryIndex index;
    private final OrderService orders;
    private final Counter expired;
    private final Counter skipped;
    private final Counter failed;

    public ExpiryWorker(ExpiryIndex index, OrderService orders, MeterRegistry registry) {
        this.index = index;
        this.orders = orders;
        this.expired = outcome(registry, "expired");
        this.skipped = outcome(registry, "skipped");
        this.failed = outcome(registry, "failed");
    }

    @Scheduled(fixedDelayString = "${ieum.order.expiry.poll-interval:PT5S}")
    public void run() {
        for (Long orderId : index.pollDue(LocalDateTime.now(), BATCH)) {
            process(orderId);
        }
    }

    void process(Long orderId) {
        try {
            orders.expire(orderId);
            expired.increment();
            index.remove(orderId);
        } catch (InvalidOrderStateException | OrderException.OrderNotFound e) {
            skipped.increment();
            index.remove(orderId);
        } catch (RuntimeException e) {
            failed.increment();
            log.warn("만료 처리 실패, 다음 주기에 재시도: orderId={}", orderId, e);
        }
    }

    private static Counter outcome(MeterRegistry registry, String outcome) {
        return Counter.builder("order.expiry").tag("outcome", outcome).register(registry);
    }
}
