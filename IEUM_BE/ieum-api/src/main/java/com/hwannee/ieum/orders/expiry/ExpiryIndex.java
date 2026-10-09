package com.hwannee.ieum.orders.expiry;

import com.hwannee.ieum.orders.stock.StockKeys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

// 만료 인덱스. Sorted Set orders:expiry 에 member = orderId, score = 만료 시각(epoch 초) 으로 둔다.
// Keyspace Notification 에 기대지 않고 ExpiryWorker 가 ZRANGEBYSCORE 로 지난 것을 꺼낸다 (README V1.1).
// 쓰기는 DB 커밋 뒤에만 나간다. 커밋 전에 ZADD 하면 롤백된 주문이 인덱스에 남고, 커밋 전에 ZREM 하면 실패한 픽업이 인덱스에서 사라진다.
@Component
public class ExpiryIndex {

    private final StringRedisTemplate redis;

    public ExpiryIndex(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void register(Long orderId, LocalDateTime expiresAt) {
        afterCommit(() -> redis.opsForZSet().add(StockKeys.EXPIRY_INDEX, String.valueOf(orderId), toScore(expiresAt)));
    }

    public void remove(Long orderId) {
        afterCommit(() -> redis.opsForZSet().remove(StockKeys.EXPIRY_INDEX, String.valueOf(orderId)));
    }

    // TODO(V3 다중 인스턴스): 워커가 여럿이면 같은 id 를 동시에 꺼낸다. ZPOPMIN 계열로 꺼내며 지우거나 워커 리더 선출이 필요
    public List<Long> pollDue(LocalDateTime now, int limit) {
        Set<String> due = redis.opsForZSet().rangeByScore(StockKeys.EXPIRY_INDEX, 0, toScore(now), 0, limit);
        if (due == null) {
            return List.of();
        }
        return due.stream().map(Long::valueOf).toList();
    }

    private static double toScore(LocalDateTime at) {
        return at.atZone(ZoneId.systemDefault()).toEpochSecond();
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
}
