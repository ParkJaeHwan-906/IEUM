package com.hwannee.ieum.orders.stock;

import com.hwannee.ieum.orders.config.OrderProperties;
import com.hwannee.ieum.orders.domain.OrderState;
import com.hwannee.ieum.orders.domain.UsersOrders;
import com.hwannee.ieum.orders.exception.OrderException;
import com.hwannee.ieum.orders.repository.UsersOrdersRepository;
import com.hwannee.ieum.stores.domain.StoresItems;
import com.hwannee.ieum.stores.repository.StoresItemsRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "ieum.stock.strategy", havingValue = "redis")
public class RedisStockDeduction implements StockDeductionStrategy {

    private static final Logger log = LoggerFactory.getLogger(RedisStockDeduction.class);
    private static final String REPLAY_PREFIX = "REPLAY:";
    private static final String PENDING = "PENDING";
    private static final String MISSING = "MISSING";
    private static final String BELOW_HELD = "BELOW_HELD";
    private static final Duration RESTORED_MARKER_TTL = Duration.ofDays(1);

    public enum Outcome { RESERVED, REPLAY, IN_FLIGHT, DUPLICATE, SOLD_OUT }

    public record Reservation(Outcome outcome, Long orderId) {
    }

    private final StringRedisTemplate redis;
    private final StoresItemsRepository items;
    private final UsersOrdersRepository orders;
    private final OrderProperties.Idempotency idempotency;
    private final RedisScript<String> reserveScript;
    private final RedisScript<Long> compensateScript;
    private final RedisScript<Long> releaseScript;
    private final RedisScript<Long> warmupScript;
    private final RedisScript<String> adjustScript;
    private final Map<Outcome, Counter> reserved = new EnumMap<>(Outcome.class);
    private final Counter warmup;
    private final Counter compensated;
    private final Counter released;
    private final Counter releaseSkipped;
    private final Counter releaseFailed;
    private final Counter adjustFailed;
    private final Timer script;

    public RedisStockDeduction(StringRedisTemplate redis, StoresItemsRepository items, UsersOrdersRepository orders,
                               OrderProperties properties, MeterRegistry registry) {
        this.redis = redis;
        this.items = items;
        this.orders = orders;
        this.idempotency = properties.idempotency();
        this.reserveScript = RedisScript.of(new ClassPathResource("redis/stock-reserve.lua"), String.class);
        this.compensateScript = RedisScript.of(new ClassPathResource("redis/stock-compensate.lua"), Long.class);
        this.releaseScript = RedisScript.of(new ClassPathResource("redis/stock-release.lua"), Long.class);
        this.warmupScript = RedisScript.of(new ClassPathResource("redis/stock-warmup.lua"), Long.class);
        this.adjustScript = RedisScript.of(new ClassPathResource("redis/stock-adjust.lua"), String.class);
        for (Outcome outcome : Outcome.values()) {
            reserved.put(outcome, Counter.builder("stock.redis.reserve")
                    .tag("outcome", outcome.name().toLowerCase()).register(registry));
        }
        this.warmup = event(registry, "warmup");
        this.compensated = event(registry, "compensated");
        this.released = event(registry, "released");
        this.releaseSkipped = event(registry, "release_skipped");
        this.releaseFailed = event(registry, "release_failed");
        this.adjustFailed = event(registry, "adjust_failed");
        this.script = Timer.builder("stock.redis.script").register(registry);
    }

    public Reservation reserve(Long itemId, String userUid, String idempotencyKey, int quantity) {
        String result = executeReserve(itemId, userUid, idempotencyKey, quantity);
        if (MISSING.equals(result)) {
            warmUp(itemId);
            result = executeReserve(itemId, userUid, idempotencyKey, quantity);
            if (MISSING.equals(result)) {
                throw new IllegalStateException("재고 키를 적재하지 못했습니다: " + StockKeys.stock(itemId));
            }
        }
        Reservation reservation = toReservation(result);
        reserved.get(reservation.outcome()).increment();
        return reservation;
    }

    public void confirm(Long itemId, String userUid, String idempotencyKey, Long orderId) {
        redis.opsForValue().set(StockKeys.idempotency(itemId, userUid, idempotencyKey),
                String.valueOf(orderId), idempotency.ttl());
    }

    public void compensate(Long itemId, String userUid, String idempotencyKey, int quantity) {
        redis.execute(compensateScript,
                List.of(StockKeys.stock(itemId), StockKeys.active(itemId),
                        StockKeys.idempotency(itemId, userUid, idempotencyKey)),
                String.valueOf(quantity), userUid);
        compensated.increment();
    }

    @Override
    public void deduct(Long itemId, int quantity) {
        throw new IllegalStateException("redis 전략의 예약은 RedisOrderCreator 가 reserve 로 처리합니다.");
    }

    @Override
    public void restore(UsersOrders order) {
        Long itemId = order.getStoresItem().getId();
        Long orderId = order.getId();
        int quantity = order.getQuantity();
        String userUid = order.getUsersAccount().getUid();
        afterCommit(() -> release(itemId, orderId, userUid, quantity));
    }

    @Override
    public void settle(UsersOrders order) {
        Long itemId = order.getStoresItem().getId();
        String userUid = order.getUsersAccount().getUid();
        afterCommit(() -> redis.opsForSet().remove(StockKeys.active(itemId), userUid));
    }

    @Override
    public void initialize(Long itemId, int quantity) {
        afterCommit(() -> redis.opsForValue().set(StockKeys.stock(itemId), String.valueOf(quantity)));
    }

    @Override
    public boolean adjust(StoresItems lockedItem, int initialQuantity) {
        Long itemId = lockedItem.getId();
        long delta = (long) initialQuantity - lockedItem.getInitialQuantity();
        if (delta < 0) {
            String result = executeAdjust(itemId, delta);
            if (MISSING.equals(result)) {
                warmUp(itemId);
                result = executeAdjust(itemId, delta);
                if (MISSING.equals(result)) {
                    throw new IllegalStateException("재고 키를 적재하지 못했습니다: " + StockKeys.stock(itemId));
                }
            }
            if (BELOW_HELD.equals(result)) {
                return false;
            }
            afterRollback(() -> increaseQuietly(itemId, -delta));
        } else if (delta > 0) {
            if (!Boolean.TRUE.equals(redis.hasKey(StockKeys.stock(itemId)))) {
                warmUp(itemId);
            }
            afterCommit(() -> increaseQuietly(itemId, delta));
        }
        lockedItem.changeInitialQuantity(initialQuantity);
        return true;
    }

    void release(Long itemId, Long orderId, String userUid, int quantity) {
        try {
            Long result = redis.execute(releaseScript,
                    List.of(StockKeys.stock(itemId), StockKeys.active(itemId), StockKeys.restored(itemId, orderId)),
                    String.valueOf(quantity), userUid, String.valueOf(RESTORED_MARKER_TTL.toSeconds()));
            (Long.valueOf(1L).equals(result) ? released : releaseSkipped).increment();
        } catch (RuntimeException e) {
            releaseFailed.increment();
            log.warn("재고 복구 실패, Reconciliation 이 정정: itemId={} orderId={}", itemId, orderId, e);
        }
    }

    void warmUp(Long itemId) {
        StoresItems item = items.findById(itemId).orElseThrow(OrderException.ItemNotFound::new);
        EnumSet<OrderState> held = EnumSet.copyOf(OrderState.ACTIVE);
        held.add(OrderState.PICKED_UP);
        long expected = item.getInitialQuantity() - orders.sumQuantityByItemAndStates(itemId, held);
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(expected));
        args.addAll(orders.findAccountUidsByItemAndStates(itemId, OrderState.ACTIVE));
        redis.execute(warmupScript, List.of(StockKeys.stock(itemId), StockKeys.active(itemId)), args.toArray());
        warmup.increment();
    }

    private void increaseQuietly(Long itemId, long amount) {
        try {
            executeAdjust(itemId, amount);
        } catch (RuntimeException e) {
            adjustFailed.increment();
            log.warn("재고 조정 반영 실패, Reconciliation 이 정정: itemId={} amount={}", itemId, amount, e);
        }
    }

    private String executeAdjust(Long itemId, long delta) {
        String result = redis.execute(adjustScript, List.of(StockKeys.stock(itemId)), String.valueOf(delta));
        if (result == null) {
            throw new IllegalStateException("재고 조정 스크립트가 결과를 돌려주지 않았습니다: " + StockKeys.stock(itemId));
        }
        return result;
    }

    private String executeReserve(Long itemId, String userUid, String idempotencyKey, int quantity) {
        String result = script.record(() -> redis.execute(reserveScript,
                List.of(StockKeys.stock(itemId), StockKeys.active(itemId),
                        StockKeys.idempotency(itemId, userUid, idempotencyKey)),
                String.valueOf(quantity), userUid, String.valueOf(idempotency.pendingTtl().toSeconds())));
        if (result == null) {
            throw new IllegalStateException("예약 스크립트가 결과를 돌려주지 않았습니다: " + StockKeys.stock(itemId));
        }
        return result;
    }

    private static Reservation toReservation(String result) {
        if (result.startsWith(REPLAY_PREFIX)) {
            String seen = result.substring(REPLAY_PREFIX.length());
            return PENDING.equals(seen)
                    ? new Reservation(Outcome.IN_FLIGHT, null)
                    : new Reservation(Outcome.REPLAY, Long.valueOf(seen));
        }
        return switch (result) {
            case "OK" -> new Reservation(Outcome.RESERVED, null);
            case "DUPLICATE" -> new Reservation(Outcome.DUPLICATE, null);
            case "SOLD_OUT" -> new Reservation(Outcome.SOLD_OUT, null);
            default -> throw new IllegalStateException("알 수 없는 예약 스크립트 결과: " + result);
        };
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private static void afterRollback(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    action.run();
                }
            }
        });
    }

    private static Counter event(MeterRegistry registry, String event) {
        return Counter.builder("stock.redis.event").tag("event", event).register(registry);
    }
}
