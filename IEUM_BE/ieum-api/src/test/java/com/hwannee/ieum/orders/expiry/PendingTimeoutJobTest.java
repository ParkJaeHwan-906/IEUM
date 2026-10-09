package com.hwannee.ieum.orders.expiry;

import com.hwannee.ieum.orders.config.OrderProperties;
import com.hwannee.ieum.orders.domain.InvalidOrderStateException;
import com.hwannee.ieum.orders.domain.OrderState;
import com.hwannee.ieum.orders.repository.UsersOrdersRepository;
import com.hwannee.ieum.orders.service.OrderService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class PendingTimeoutJobTest {

    @Mock
    UsersOrdersRepository orders;

    @Mock
    OrderService orderService;

    SimpleMeterRegistry registry;
    PendingTimeoutJob job;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        OrderProperties properties = new OrderProperties(Duration.ofMinutes(15),
                new OrderProperties.Retry(3, Duration.ofMillis(10)),
                new OrderProperties.Idempotency(Duration.ofDays(1), Duration.ofSeconds(30)), Duration.ofSeconds(5),
                Duration.ofMinutes(5));
        job = new PendingTimeoutJob(orders, orderService, properties, registry);
    }

    @Test
    void 승인_제한_시간이_지난_PENDING_을_찾아_취소한다() {
        given(orders.findPendingIdsCreatedBefore(any(), any())).willReturn(List.of(1L, 2L));
        LocalDateTime before = LocalDateTime.now().minusMinutes(5);

        job.run();

        ArgumentCaptor<LocalDateTime> threshold = ArgumentCaptor.forClass(LocalDateTime.class);
        then(orders).should().findPendingIdsCreatedBefore(threshold.capture(), any(Pageable.class));
        assertThat(threshold.getValue()).isAfterOrEqualTo(before).isBefore(before.plusSeconds(5));
        then(orderService).should().cancelUnapproved(1L);
        then(orderService).should().cancelUnapproved(2L);
        assertThat(count("canceled")).isEqualTo(2);
    }

    @Test
    void 그_사이_승인되었거나_동시에_바뀐_주문은_건너뛴다() {
        given(orders.findPendingIdsCreatedBefore(any(), any())).willReturn(List.of(1L, 2L));
        willThrow(new InvalidOrderStateException(OrderState.APPROVED, "미승인 자동 취소"))
                .given(orderService).cancelUnapproved(1L);
        willThrow(new ObjectOptimisticLockingFailureException(Object.class, 2L))
                .given(orderService).cancelUnapproved(2L);

        job.run();

        assertThat(count("skipped")).isEqualTo(2);
        assertThat(count("canceled")).isZero();
    }

    @Test
    void 한_묶음이_가득_차면_다음_묶음을_이어서_처리한다() {
        List<Long> full = LongStream.rangeClosed(1, 100).boxed().toList();
        given(orders.findPendingIdsCreatedBefore(any(), any())).willReturn(full, List.of(101L));

        job.run();

        then(orders).should(times(2)).findPendingIdsCreatedBefore(any(), any());
        assertThat(count("canceled")).isEqualTo(101);
    }

    @Test
    void 묶음_전체가_실패하면_다음_주기로_미룬다() {
        List<Long> full = LongStream.rangeClosed(1, 100).boxed().toList();
        given(orders.findPendingIdsCreatedBefore(any(), any())).willReturn(full);
        willThrow(new DataAccessResourceFailureException("db down")).given(orderService).cancelUnapproved(any());

        job.run();

        then(orders).should(times(1)).findPendingIdsCreatedBefore(any(), any());
        assertThat(count("failed")).isEqualTo(100);
    }

    private double count(String outcome) {
        return registry.get("order.pending.timeout").tag("outcome", outcome).counter().count();
    }
}
