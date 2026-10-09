package com.hwannee.ieum.orders.expiry;

import com.hwannee.ieum.orders.config.OrderProperties;
import com.hwannee.ieum.orders.domain.InvalidOrderStateException;
import com.hwannee.ieum.orders.exception.OrderException;
import com.hwannee.ieum.orders.repository.UsersOrdersRepository;
import com.hwannee.ieum.orders.service.OrderService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class PendingTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(PendingTimeoutJob.class);
    private static final int BATCH = 100;

    private final UsersOrdersRepository orders;
    private final OrderService orderService;
    private final OrderProperties properties;
    private final Counter canceled;
    private final Counter skipped;
    private final Counter failed;

    public PendingTimeoutJob(UsersOrdersRepository orders, OrderService orderService, OrderProperties properties,
                             MeterRegistry registry) {
        this.orders = orders;
        this.orderService = orderService;
        this.properties = properties;
        this.canceled = outcome(registry, "canceled");
        this.skipped = outcome(registry, "skipped");
        this.failed = outcome(registry, "failed");
    }

    @Scheduled(fixedDelayString = "${ieum.order.approval-timeout-poll-interval:PT10S}")
    public void run() {
        LocalDateTime threshold = LocalDateTime.now().minus(properties.approvalTimeout());
        List<Long> due;
        do {
            due = orders.findPendingIdsCreatedBefore(threshold, PageRequest.of(0, BATCH));
            int handled = 0;
            for (Long orderId : due) {
                if (process(orderId)) {
                    handled++;
                }
            }
            if (handled == 0) {
                return;
            }
        } while (due.size() == BATCH);
    }

    boolean process(Long orderId) {
        try {
            orderService.cancelUnapproved(orderId);
            canceled.increment();
            return true;
        } catch (InvalidOrderStateException | OrderException.OrderNotFound | ConcurrencyFailureException e) {
            skipped.increment();
            return true;
        } catch (RuntimeException e) {
            failed.increment();
            log.warn("미승인 예약 자동 취소 실패, 다음 주기에 재시도: orderId={}", orderId, e);
            return false;
        }
    }

    private static Counter outcome(MeterRegistry registry, String outcome) {
        return Counter.builder("order.pending.timeout").tag("outcome", outcome).register(registry);
    }
}
