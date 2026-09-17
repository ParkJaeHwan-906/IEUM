package com.hwannee.ieum.orders.service;

import com.hwannee.ieum.auth.verify.principal.AuthenticatedUser;
import com.hwannee.ieum.orders.config.OrderProperties;
import com.hwannee.ieum.orders.domain.OrderState;
import com.hwannee.ieum.orders.exception.OrderException;
import com.hwannee.ieum.orders.web.dto.CreateOrderRequest;
import com.hwannee.ieum.orders.web.dto.OrderResponse;
import com.hwannee.ieum.stores.domain.StoresItems;
import com.hwannee.ieum.users.domain.UserType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class OrderCreateRetrierTest {

    private static final String ITEM_UID = "item-uid";

    @Mock
    OrderService orderService;

    SimpleMeterRegistry registry;
    AuthenticatedUser user;
    CreateOrderRequest request;
    OrderResponse response;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        user = new AuthenticatedUser("consumer-uid", UserType.CONSUMER, "nick");
        request = new CreateOrderRequest(ITEM_UID, 1);
        response = new OrderResponse(100L, ITEM_UID, "빵", 1, 3000, OrderState.PENDING, null, null);
    }

    @Test
    void 두_번_충돌_후_성공하면_세_번_호출하고_응답을_돌려준다() {
        OrderCreateRetrier retrier = retrier(3);
        given(orderService.create(user, request, null))
                .willThrow(conflict())
                .willThrow(conflict())
                .willReturn(response);

        OrderResponse result = retrier.create(user, request, null);

        assertThat(result).isSameAs(response);
        then(orderService).should(times(3)).create(user, request, null);
        assertThat(count("success")).isEqualTo(1);
        assertThat(count("conflict")).isEqualTo(2);
        assertThat(count("exhausted")).isEqualTo(0);
        assertThat(attemptsUsedCount()).isEqualTo(1);
        assertThat(attemptsUsedMax()).isEqualTo(3);
    }

    @Test
    void 상한까지_충돌하면_예외를_그대로_전파한다() {
        OrderCreateRetrier retrier = retrier(3);
        given(orderService.create(user, request, null))
                .willThrow(conflict())
                .willThrow(conflict())
                .willThrow(conflict());

        assertThatThrownBy(() -> retrier.create(user, request, null))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        then(orderService).should(times(3)).create(user, request, null);
        assertThat(count("success")).isEqualTo(0);
        assertThat(count("conflict")).isEqualTo(3);
        assertThat(count("exhausted")).isEqualTo(1);
        assertThat(attemptsUsedCount()).isEqualTo(0);
    }

    @Test
    void 데드락_후_성공하면_재시도하고_deadlock_을_센다() {
        OrderCreateRetrier retrier = retrier(3);
        given(orderService.create(user, request, null))
                .willThrow(deadlock())
                .willReturn(response);

        OrderResponse result = retrier.create(user, request, null);

        assertThat(result).isSameAs(response);
        then(orderService).should(times(2)).create(user, request, null);
        assertThat(count("success")).isEqualTo(1);
        assertThat(count("deadlock")).isEqualTo(1);
        assertThat(count("conflict")).isEqualTo(0);
        assertThat(count("exhausted")).isEqualTo(0);
        assertThat(attemptsUsedMax()).isEqualTo(2);
    }

    @Test
    void 상한까지_데드락이면_예외를_그대로_전파한다() {
        OrderCreateRetrier retrier = retrier(3);
        given(orderService.create(user, request, null))
                .willThrow(deadlock())
                .willThrow(deadlock())
                .willThrow(deadlock());

        assertThatThrownBy(() -> retrier.create(user, request, null))
                .isInstanceOf(CannotAcquireLockException.class);

        then(orderService).should(times(3)).create(user, request, null);
        assertThat(count("success")).isEqualTo(0);
        assertThat(count("deadlock")).isEqualTo(3);
        assertThat(count("conflict")).isEqualTo(0);
        assertThat(count("exhausted")).isEqualTo(1);
        assertThat(attemptsUsedCount()).isEqualTo(0);
    }

    @Test
    void 충돌과_데드락이_섞여도_각각_센다() {
        OrderCreateRetrier retrier = retrier(3);
        given(orderService.create(user, request, null))
                .willThrow(conflict())
                .willThrow(deadlock())
                .willReturn(response);

        OrderResponse result = retrier.create(user, request, null);

        assertThat(result).isSameAs(response);
        then(orderService).should(times(3)).create(user, request, null);
        assertThat(count("success")).isEqualTo(1);
        assertThat(count("conflict")).isEqualTo(1);
        assertThat(count("deadlock")).isEqualTo(1);
        assertThat(count("exhausted")).isEqualTo(0);
        assertThat(attemptsUsedMax()).isEqualTo(3);
    }

    @Test
    void 락_대기_타임아웃은_재시도_없이_즉시_전파한다() {
        OrderCreateRetrier retrier = retrier(3);
        PessimisticLockingFailureException timeout = new PessimisticLockingFailureException("lock wait timeout");
        given(orderService.create(user, request, null)).willThrow(timeout);

        assertThatThrownBy(() -> retrier.create(user, request, null)).isSameAs(timeout);

        then(orderService).should(times(1)).create(user, request, null);
        assertThat(count("success")).isEqualTo(0);
        assertThat(count("conflict")).isEqualTo(0);
        assertThat(count("deadlock")).isEqualTo(0);
        assertThat(count("exhausted")).isEqualTo(0);
    }

    @Test
    void InsufficientStock_은_재시도_없이_즉시_전파한다() {
        OrderCreateRetrier retrier = retrier(3);
        given(orderService.create(user, request, null))
                .willThrow(new OrderException.InsufficientStock(0));

        assertThatThrownBy(() -> retrier.create(user, request, null))
                .isInstanceOf(OrderException.InsufficientStock.class);

        then(orderService).should(times(1)).create(user, request, null);
        assertThat(count("success")).isEqualTo(0);
        assertThat(count("conflict")).isEqualTo(0);
        assertThat(count("deadlock")).isEqualTo(0);
        assertThat(count("exhausted")).isEqualTo(0);
    }

    @Test
    void 첫_시도에_성공하면_한_번만_호출한다() {
        OrderCreateRetrier retrier = retrier(3);
        given(orderService.create(user, request, null)).willReturn(response);

        OrderResponse result = retrier.create(user, request, null);

        assertThat(result).isSameAs(response);
        then(orderService).should(times(1)).create(user, request, null);
        assertThat(count("success")).isEqualTo(1);
        assertThat(count("conflict")).isEqualTo(0);
        assertThat(count("deadlock")).isEqualTo(0);
        assertThat(attemptsUsedMax()).isEqualTo(1);
    }

    @Test
    void 상한이_1이면_재시도하지_않는다() {
        OrderCreateRetrier retrier = retrier(1);
        given(orderService.create(user, request, null)).willThrow(conflict());

        assertThatThrownBy(() -> retrier.create(user, request, null))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        then(orderService).should(times(1)).create(user, request, null);
        assertThat(count("conflict")).isEqualTo(1);
        assertThat(count("exhausted")).isEqualTo(1);
    }

    private OrderCreateRetrier retrier(int maxAttempts) {
        OrderProperties properties = new OrderProperties(
                Duration.ofMinutes(15), new OrderProperties.Retry(maxAttempts, Duration.ZERO));
        return new OrderCreateRetrier(orderService, properties, registry);
    }

    private static ObjectOptimisticLockingFailureException conflict() {
        return new ObjectOptimisticLockingFailureException(StoresItems.class, 1L);
    }

    private static CannotAcquireLockException deadlock() {
        return new CannotAcquireLockException("Deadlock found when trying to get lock; try restarting transaction");
    }

    private double count(String outcome) {
        return registry.get("order.create.attempts").tag("outcome", outcome).counter().count();
    }

    private long attemptsUsedCount() {
        return registry.get("order.create.attempts.used").summary().count();
    }

    private double attemptsUsedMax() {
        return registry.get("order.create.attempts.used").summary().max();
    }
}
