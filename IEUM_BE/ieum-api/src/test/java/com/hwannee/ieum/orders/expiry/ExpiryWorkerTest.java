package com.hwannee.ieum.orders.expiry;

import com.hwannee.ieum.orders.domain.InvalidOrderStateException;
import com.hwannee.ieum.orders.domain.OrderState;
import com.hwannee.ieum.orders.exception.OrderException;
import com.hwannee.ieum.orders.service.OrderService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class ExpiryWorkerTest {

    @Mock
    ExpiryIndex index;

    @Mock
    OrderService orders;

    SimpleMeterRegistry registry;
    ExpiryWorker worker;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        worker = new ExpiryWorker(index, orders, registry);
    }

    @Test
    void 만료된_주문은_expire_후_인덱스에서_지운다() {
        given(index.pollDue(any(LocalDateTime.class), anyInt())).willReturn(List.of(1L, 2L));

        worker.run();

        then(orders).should().expire(1L);
        then(orders).should().expire(2L);
        then(index).should().remove(1L);
        then(index).should().remove(2L);
        assertThat(count("expired")).isEqualTo(2);
    }

    @Test
    void 이미_픽업되거나_취소된_주문은_건너뛰고_인덱스에서_지운다() {
        willThrow(new InvalidOrderStateException(OrderState.PICKED_UP, "만료 처리")).given(orders).expire(1L);

        worker.process(1L);

        then(index).should().remove(1L);
        assertThat(count("skipped")).isEqualTo(1);
        assertThat(count("expired")).isZero();
    }

    @Test
    void 없는_주문도_건너뛰고_인덱스에서_지운다() {
        willThrow(new OrderException.OrderNotFound()).given(orders).expire(1L);

        worker.process(1L);

        then(index).should().remove(1L);
        assertThat(count("skipped")).isEqualTo(1);
    }

    @Test
    void 일시적_실패면_인덱스에_남겨_다음_주기에_다시_시도한다() {
        willThrow(new DataAccessResourceFailureException("db down")).given(orders).expire(1L);

        worker.process(1L);

        then(index).should(never()).remove(anyLong());
        assertThat(count("failed")).isEqualTo(1);
    }

    private double count(String outcome) {
        return registry.get("order.expiry").tag("outcome", outcome).counter().count();
    }
}
