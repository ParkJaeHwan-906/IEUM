package com.hwannee.ieum.orders.expiry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class ExpiryIndexTest {

    private static final String KEY = "orders:expiry";

    @Mock
    StringRedisTemplate redis;

    @Mock
    ZSetOperations<String, String> zset;

    ExpiryIndex index;

    @BeforeEach
    void setUp() {
        index = new ExpiryIndex(redis);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void 트랜잭션_밖_register_는_즉시_ZADD_하고_score_는_만료_시각의_epoch_초다() {
        given(redis.opsForZSet()).willReturn(zset);
        LocalDateTime expiresAt = LocalDateTime.of(2026, 9, 23, 12, 0);

        index.register(7L, expiresAt);

        then(zset).should().add(KEY, "7", (double) expiresAt.atZone(ZoneId.systemDefault()).toEpochSecond());
    }

    @Test
    void 트랜잭션_안_register_는_커밋_후에만_ZADD() {
        TransactionSynchronizationManager.initSynchronization();
        given(redis.opsForZSet()).willReturn(zset);

        index.register(7L, LocalDateTime.now());
        then(zset).should(never()).add(anyString(), anyString(), anyDouble());

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        then(zset).should().add(anyString(), anyString(), anyDouble());
    }

    @Test
    void 트랜잭션_안_remove_는_롤백되면_실행되지_않는다() {
        TransactionSynchronizationManager.initSynchronization();

        index.remove(7L);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        then(redis).should(never()).opsForZSet();
    }

    @Test
    void pollDue_는_지난_score_만_상한_개수만큼_돌려준다() {
        given(redis.opsForZSet()).willReturn(zset);
        LocalDateTime now = LocalDateTime.of(2026, 9, 23, 12, 0);
        double nowScore = now.atZone(ZoneId.systemDefault()).toEpochSecond();
        given(zset.rangeByScore(KEY, 0, nowScore, 0, 100)).willReturn(new LinkedHashSet<>(java.util.List.of("3", "5")));

        assertThat(index.pollDue(now, 100)).containsExactly(3L, 5L);
    }

    @Test
    void pollDue_는_결과가_null_이면_빈_목록() {
        given(redis.opsForZSet()).willReturn(zset);
        given(zset.rangeByScore(anyString(), anyDouble(), anyDouble(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong())).willReturn(null);

        assertThat(index.pollDue(LocalDateTime.now(), 10)).isEmpty();
    }
}
