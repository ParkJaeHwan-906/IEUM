package com.hwannee.ieum.orders.stock;

import com.hwannee.ieum.orders.exception.OrderException;
import com.hwannee.ieum.stores.domain.StoreType;
import com.hwannee.ieum.stores.domain.Stores;
import com.hwannee.ieum.stores.domain.StoresItems;
import com.hwannee.ieum.stores.repository.StoresItemsRepository;
import com.hwannee.ieum.users.domain.UserType;
import com.hwannee.ieum.users.domain.UsersAccount;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "REDIS_IT", matches = "true")
class RedisStockDeductionIT {

    private static final long ITEM_ID = 999L;
    private static final String KEY = "stock:999";

    static LettuceConnectionFactory factory;
    StringRedisTemplate redis;
    RedisStockDeduction strategy;

    @BeforeAll
    static void connect() {
        RedisStandaloneConfiguration conf = new RedisStandaloneConfiguration(
                System.getenv().getOrDefault("REDIS_HOST", "localhost"),
                Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379")));
        conf.setPassword(RedisPassword.of(System.getenv("REDIS_PASSWORD")));
        factory = new LettuceConnectionFactory(conf);
        factory.afterPropertiesSet();
        factory.start();
    }

    @AfterAll
    static void disconnect() {
        factory.destroy();
    }

    @BeforeEach
    void setUp() {
        redis = new StringRedisTemplate(factory);
        strategy = new RedisStockDeduction(redis, Mockito.mock(StoresItemsRepository.class), new SimpleMeterRegistry());
        redis.opsForValue().set(KEY, "10");
    }

    @AfterEach
    void tearDown() {
        redis.delete(KEY);
    }

    @Test
    void 동시에_100건이_차감해도_정확히_10건만_성공한다() throws Exception {
        int threads = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger sold = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    strategy.deduct(ITEM_ID, 1);
                    ok.incrementAndGet();
                } catch (OrderException.InsufficientStock e) {
                    sold.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertThat(ok.get()).isEqualTo(10);
        assertThat(sold.get()).isEqualTo(90);
        assertThat(redis.opsForValue().get(KEY)).isEqualTo("0");
    }

    @Test
    void 키가_없으면_DB_값으로_채우고_차감한다() {
        redis.delete(KEY);
        StoresItemsRepository items = Mockito.mock(StoresItemsRepository.class);
        Mockito.when(items.findById(ITEM_ID)).thenReturn(Optional.of(item(3)));
        strategy = new RedisStockDeduction(redis, items, new SimpleMeterRegistry());

        strategy.deduct(ITEM_ID, 1);

        assertThat(redis.opsForValue().get(KEY)).isEqualTo("2");
    }

    @Test
    void restore_는_트랜잭션_밖에서_즉시_INCRBY() {
        strategy.restore(ITEM_ID, 5);

        assertThat(redis.opsForValue().get(KEY)).isEqualTo("15");
    }

    private static StoresItems item(int remaining) {
        UsersAccount owner = new UsersAccount(null, UserType.BUSINESS_OWNER, "owner-uid", "owner", "encoded");
        Stores store = new Stores(owner, "store-uid", "가게", StoreType.CAFE, LocalTime.of(9, 0), LocalTime.of(18, 0));
        StoresItems item = new StoresItems(store, "item-uid", null, "빵", 5000, 3000, 100, LocalDateTime.now().plusHours(1));
        ReflectionTestUtils.setField(item, "id", ITEM_ID);
        ReflectionTestUtils.setField(item, "remainingQuantity", remaining);
        return item;
    }
}
