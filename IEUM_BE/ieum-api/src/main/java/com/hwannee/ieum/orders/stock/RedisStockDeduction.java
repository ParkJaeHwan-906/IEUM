package com.hwannee.ieum.orders.stock;

import com.hwannee.ieum.orders.exception.OrderException;
import com.hwannee.ieum.stores.domain.StoresItems;
import com.hwannee.ieum.stores.repository.StoresItemsRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

@Component
@ConditionalOnProperty(name = "ieum.stock.strategy", havingValue = "redis")
public class RedisStockDeduction implements StockDeductionStrategy {

    private static final long INSUFFICIENT = -1L;
    private static final long MISSING = -2L;

    private final StringRedisTemplate redis;
    private final StoresItemsRepository items;
    private final RedisScript<Long> deductScript;
    private final Counter success;
    private final Counter insufficient;
    private final Counter warmup;
    private final Counter compensated;
    private final Timer script;

    public RedisStockDeduction(StringRedisTemplate redis, StoresItemsRepository items, MeterRegistry registry) {
        this.redis = redis;
        this.items = items;
        this.deductScript = RedisScript.of(new ClassPathResource("redis/stock-deduct.lua"), Long.class);
        this.success = outcome(registry, "success");
        this.insufficient = outcome(registry, "insufficient");
        this.warmup = outcome(registry, "warmup");
        this.compensated = outcome(registry, "compensated");
        this.script = Timer.builder("stock.redis.script").register(registry);
    }

    @Override
    public void deduct(Long itemId, int quantity) {
        String key = key(itemId);
        long result = execute(key, quantity);
        if(result == MISSING) {
            warmup.increment();
            warmUp(itemId, key);
            result = execute(key, quantity);
            if(result == MISSING) {
                throw new IllegalStateException("재고 키를 적재하지 못했습니다: " + key);
            }
        }
        if(result == INSUFFICIENT) {
            insufficient.increment();
            throw new OrderException.InsufficientStock();
        }
        success.increment();
        onRollback(() -> {
            redis.opsForValue().increment(key, quantity);
            compensated.increment();
        });
    }

    @Override
    public void restore(Long itemId, int quantity) {
        afterCommit(() -> redis.opsForValue().increment(key(itemId), quantity));
    }

    @Override
    public void initialize(Long itemId, int quantity) {
        afterCommit(() -> redis.opsForValue().set(key(itemId), String.valueOf(quantity)));
    }

    private long execute(String key, int quantity) {
        Long result = script.record(() -> redis.execute(deductScript, List.of(key), String.valueOf(quantity)));
        if (result == null) {
            throw new IllegalStateException("재고 스크립트가 결과를 돌려주지 않았습니다: " + key);
        }
        return result;
    }

    private void warmUp(Long itemId, String key) {
        StoresItems item = items.findById(itemId).orElseThrow(OrderException.ItemNotFound::new);
        redis.opsForValue().setIfAbsent(key, String.valueOf(item.getRemainingQuantity()));
    }

    private static void onRollback(Runnable action) {
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

    private static String key(Long itemId) {
        return StockKeys.stock(itemId);
    }

    private static Counter outcome(MeterRegistry registry, String outcome) {
        return Counter.builder("stock.redis.deduct").tag("outcome", outcome).register(registry);
    }
}
