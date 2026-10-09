package com.hwannee.ieum.orders.reconcile;

import com.hwannee.ieum.orders.config.OrderProperties;
import com.hwannee.ieum.orders.domain.OrderState;
import com.hwannee.ieum.orders.domain.UsersOrders;
import com.hwannee.ieum.orders.expiry.ExpiryIndex;
import com.hwannee.ieum.orders.repository.UsersOrdersRepository;
import com.hwannee.ieum.orders.stock.StockKeys;
import com.hwannee.ieum.stores.domain.StoresItems;
import com.hwannee.ieum.stores.repository.StoresItemsRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name = "ieum.stock.strategy", havingValue = "redis")
public class StockReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(StockReconciliationJob.class);
    private static final int PAGE_SIZE = 100;
    private static final Set<OrderState> HELD = EnumSet.of(
            OrderState.PENDING, OrderState.APPROVED, OrderState.READY_FOR_PICKUP, OrderState.PICKED_UP);

    record Drift(long expected, long actual, Set<String> extraMembers, Set<String> missingMembers) {

        long stockDelta() {
            return expected - actual;
        }

        boolean isEmpty() {
            return stockDelta() == 0 && extraMembers.isEmpty() && missingMembers.isEmpty();
        }
    }

    private final StoresItemsRepository items;
    private final UsersOrdersRepository orders;
    private final StringRedisTemplate redis;
    private final ExpiryIndex expiryIndex;
    private final OrderProperties properties;
    private final TransactionTemplate tx;
    private final Map<Long, Drift> previous = new ConcurrentHashMap<>();
    private final Counter checked;
    private final Counter mismatch;
    private final Counter missing;
    private final Counter corrected;
    private final Counter requeued;

    public StockReconciliationJob(StoresItemsRepository items, UsersOrdersRepository orders,
                                  StringRedisTemplate redis, ExpiryIndex expiryIndex, OrderProperties properties,
                                  PlatformTransactionManager transactionManager, MeterRegistry registry) {
        this.items = items;
        this.orders = orders;
        this.redis = redis;
        this.expiryIndex = expiryIndex;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
        this.checked = outcome(registry, "checked");
        this.mismatch = outcome(registry, "mismatch");
        this.missing = outcome(registry, "missing");
        this.corrected = outcome(registry, "corrected");
        this.requeued = outcome(registry, "requeued");
    }

    @Scheduled(fixedDelayString = "${ieum.order.reconciliation.interval:PT60S}")
    public void run() {
        Page<StoresItems> page;
        int number = 0;
        do {
            PageRequest request = PageRequest.of(number++, PAGE_SIZE, Sort.by("id"));
            page = items.findAll(request);
            for (StoresItems item : page) {
                tx.executeWithoutResult(status -> check(item.getId()));
            }
        } while (page.hasNext());
        tx.executeWithoutResult(status -> requeueMissedExpiries());
    }

    void check(Long itemId) {
        StoresItems item = items.findById(itemId).orElse(null);
        if (item == null) {
            previous.remove(itemId);
            return;
        }
        long expected = item.getInitialQuantity() - orders.sumQuantityByItemAndStates(itemId, HELD);
        Set<String> expectedMembers = new HashSet<>(orders.findAccountUidsByItemAndStates(itemId, OrderState.ACTIVE));
        if (item.getRemainingQuantity() != expected) {
            items.overwriteRemainingQuantity(itemId, (int) expected);
        }

        String actual = redis.opsForValue().get(StockKeys.stock(itemId));
        checked.increment();
        if (actual == null) {
            missing.increment();
            previous.remove(itemId);
            return;
        }
        Set<String> actualMembers = redis.opsForSet().members(StockKeys.active(itemId));
        Drift now = drift(expected, Long.parseLong(actual), expectedMembers,
                actualMembers == null ? Set.of() : actualMembers);
        if (now.isEmpty()) {
            previous.remove(itemId);
            return;
        }

        mismatch.increment();
        Drift before = previous.put(itemId, now);
        log.warn("재고 불일치: itemId={} expected={} redis={} drift={} persisted={}",
                itemId, expected, actual, now, now.equals(before));
        if (now.equals(before)) {
            correct(itemId, now);
            previous.remove(itemId);
        }
    }

    void requeueMissedExpiries() {
        LocalDateTime threshold = LocalDateTime.now().minus(properties.pickupTtl());
        List<UsersOrders> overdue = orders.findReadyForPickupBefore(threshold);
        for (UsersOrders order : overdue) {
            expiryIndex.register(order.getId(), order.getReadyAt().plus(properties.pickupTtl()));
            requeued.increment();
        }
    }

    private void correct(Long itemId, Drift drift) {
        if (drift.stockDelta() != 0) {
            redis.opsForValue().increment(StockKeys.stock(itemId), drift.stockDelta());
            corrected.increment();
        }
        if (!drift.extraMembers().isEmpty()) {
            redis.opsForSet().remove(StockKeys.active(itemId), drift.extraMembers().toArray());
            corrected.increment();
        }
        if (!drift.missingMembers().isEmpty()) {
            redis.opsForSet().add(StockKeys.active(itemId), drift.missingMembers().toArray(String[]::new));
            corrected.increment();
        }
    }

    private static Drift drift(long expected, long actual, Set<String> expectedMembers, Set<String> actualMembers) {
        Set<String> extra = new HashSet<>(actualMembers);
        extra.removeAll(expectedMembers);
        Set<String> lost = new HashSet<>(expectedMembers);
        lost.removeAll(actualMembers);
        return new Drift(expected, actual, extra, lost);
    }

    private static Counter outcome(MeterRegistry registry, String outcome) {
        return Counter.builder("stock.reconciliation").tag("outcome", outcome).register(registry);
    }
}
