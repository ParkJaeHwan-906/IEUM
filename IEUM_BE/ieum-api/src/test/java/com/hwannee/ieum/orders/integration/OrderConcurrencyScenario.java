package com.hwannee.ieum.orders.integration;

import com.hwannee.ieum.auth.verify.principal.AuthenticatedUser;
import com.hwannee.ieum.orders.domain.OrderState;
import com.hwannee.ieum.orders.domain.UsersOrders;
import com.hwannee.ieum.orders.exception.OrderException;
import com.hwannee.ieum.orders.repository.UsersOrdersRepository;
import com.hwannee.ieum.orders.service.OrderCreator;
import com.hwannee.ieum.orders.service.OrderService;
import com.hwannee.ieum.orders.web.dto.CreateOrderRequest;
import com.hwannee.ieum.orders.web.dto.OrderResponse;
import com.hwannee.ieum.stores.domain.StoreType;
import com.hwannee.ieum.stores.domain.Stores;
import com.hwannee.ieum.stores.domain.StoresItems;
import com.hwannee.ieum.stores.repository.StoresItemsRepository;
import com.hwannee.ieum.stores.repository.StoresRepository;
import com.hwannee.ieum.users.domain.UserType;
import com.hwannee.ieum.users.domain.Users;
import com.hwannee.ieum.users.domain.UsersAccount;
import com.hwannee.ieum.users.repository.UsersAccountRepository;
import com.hwannee.ieum.users.repository.UsersRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.ConcurrencyFailureException;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
abstract class OrderConcurrencyScenario extends ContainersSupport {

    static final int USERS = 40;
    static final int REQUESTS_PER_USER = 10;
    static final int STOCK = 25;
    static final AtomicLong SEQUENCE = new AtomicLong();

    enum Result { CREATED, SOLD_OUT, DUPLICATE, IN_FLIGHT, CONTENTION, ERROR }

    @Autowired
    OrderCreator creator;

    @Autowired
    OrderService orderService;

    @Autowired
    UsersOrdersRepository orders;

    @Autowired
    StoresItemsRepository items;

    @Autowired
    StoresRepository stores;

    @Autowired
    UsersRepository users;

    @Autowired
    UsersAccountRepository accounts;

    abstract boolean closesDuplicateGap();

    abstract long ledgerRemaining(StoresItems item);

    @Test
    void 동시_중복_요청에도_재고를_넘겨_팔지_않는다() throws Exception {
        StoresItems item = item(STOCK);
        List<AuthenticatedUser> consumers = consumers(USERS);
        List<Runnable> tasks = new ArrayList<>();
        Map<Result, AtomicLong> results = new ConcurrentHashMap<>();
        for (AuthenticatedUser consumer : consumers) {
            for (int i = 0; i < REQUESTS_PER_USER; i++) {
                tasks.add(() -> results.computeIfAbsent(
                        send(consumer, item, UUID.randomUUID().toString()), r -> new AtomicLong()).incrementAndGet());
            }
        }

        runConcurrently(tasks);

        List<UsersOrders> placed = orders.findAll().stream()
                .filter(o -> o.getStoresItem().getId().equals(item.getId()))
                .toList();
        long created = count(results, Result.CREATED);
        assertThat(count(results, Result.ERROR)).isZero();
        assertThat(placed).hasSize((int) created);
        assertThat(created).isLessThanOrEqualTo(STOCK);
        assertThat(ledgerRemaining(item)).isEqualTo(STOCK - created);
        if (closesDuplicateGap()) {
            assertThat(created).isEqualTo(STOCK);
            assertThat(ordersPerUser(placed).values()).allMatch(n -> n == 1);
        }
    }

    @Test
    void 같은_멱등키로_동시에_보내도_주문은_하나고_재고는_한_번만_빠진다() throws Exception {
        StoresItems item = item(STOCK);
        AuthenticatedUser consumer = consumers(1).getFirst();
        String key = UUID.randomUUID().toString();
        Set<Long> orderIds = ConcurrentHashMap.newKeySet();
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> {
                try {
                    orderIds.add(creator.create(consumer, new CreateOrderRequest(item.getUid(), 1), key).orderId());
                } catch (OrderException.IdempotencyInProgress | OrderException.DuplicateActiveOrder
                         | ConcurrencyFailureException ignored) {
                }
            });
        }

        runConcurrently(tasks);
        OrderResponse replay = creator.create(consumer, new CreateOrderRequest(item.getUid(), 1), key);

        assertThat(orderIds).hasSize(1).containsExactly(replay.orderId());
        assertThat(ledgerRemaining(item)).isEqualTo(STOCK - 1);
    }

    @Test
    void 같은_멱등키를_다른_상품에_쓰면_거절한다() {
        StoresItems first = item(STOCK);
        StoresItems second = item(STOCK);
        AuthenticatedUser consumer = consumers(1).getFirst();
        String key = UUID.randomUUID().toString();
        creator.create(consumer, new CreateOrderRequest(first.getUid(), 1), key);

        Result result = send(consumer, second, key);

        assertThat(result).isEqualTo(Result.ERROR);
        assertThat(ledgerRemaining(second)).isEqualTo(STOCK);
    }

    @Test
    void 취소하면_재고가_돌아오고_다시_예약할_수_있다() {
        StoresItems item = item(1);
        AuthenticatedUser consumer = consumers(1).getFirst();
        OrderResponse first = creator.create(consumer, new CreateOrderRequest(item.getUid(), 1), UUID.randomUUID().toString());
        assertThat(ledgerRemaining(item)).isZero();

        orderService.cancel(consumer, first.orderId());

        assertThat(ledgerRemaining(item)).isEqualTo(1);
        OrderResponse second = creator.create(consumer, new CreateOrderRequest(item.getUid(), 1), UUID.randomUUID().toString());
        assertThat(second.state()).isEqualTo(OrderState.PENDING);
        assertThat(ledgerRemaining(item)).isZero();
    }

    Result send(AuthenticatedUser consumer, StoresItems item, String key) {
        try {
            creator.create(consumer, new CreateOrderRequest(item.getUid(), 1), key);
            return Result.CREATED;
        } catch (OrderException.InsufficientStock e) {
            return Result.SOLD_OUT;
        } catch (OrderException.DuplicateActiveOrder e) {
            return Result.DUPLICATE;
        } catch (OrderException.IdempotencyInProgress e) {
            return Result.IN_FLIGHT;
        } catch (ConcurrencyFailureException e) {
            return Result.CONTENTION;
        } catch (RuntimeException e) {
            return Result.ERROR;
        }
    }

    StoresItems item(int stock) {
        UsersAccount owner = account(UserType.BUSINESS_OWNER);
        Stores store = stores.save(new Stores(owner, UUID.randomUUID().toString(), "가게", StoreType.BAKERY,
                LocalTime.of(0, 0), LocalTime.of(23, 59)));
        return items.save(new StoresItems(store, UUID.randomUUID().toString(), null, "빵", 5000, 3000, stock,
                LocalDateTime.now().plusDays(1)));
    }

    List<AuthenticatedUser> consumers(int count) {
        List<AuthenticatedUser> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UsersAccount account = account(UserType.CONSUMER);
            result.add(new AuthenticatedUser(account.getUid(), UserType.CONSUMER, account.getNickname()));
        }
        return result;
    }

    private UsersAccount account(UserType type) {
        String uid = UUID.randomUUID().toString();
        long seq = SEQUENCE.incrementAndGet();
        Users user = users.save(new Users("it", String.format("018%08d", seq), uid + "@it.ieum"));
        return accounts.save(new UsersAccount(user, type, uid, "it-" + seq, "encoded"));
    }

    private static void runConcurrently(List<Runnable> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(64);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Runnable task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    task.run();
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static Map<String, Long> ordersPerUser(List<UsersOrders> placed) {
        return placed.stream().collect(Collectors.groupingBy(o -> o.getUsersAccount().getId().toString(),
                Collectors.counting()));
    }

    private static long count(Map<Result, AtomicLong> results, Result result) {
        AtomicLong value = results.get(result);
        return value == null ? 0 : value.get();
    }
}
