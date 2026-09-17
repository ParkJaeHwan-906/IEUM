package com.hwannee.ieum.orders.service;

import com.hwannee.ieum.auth.verify.principal.AuthenticatedUser;
import com.hwannee.ieum.orders.config.OrderProperties;
import com.hwannee.ieum.orders.web.dto.CreateOrderRequest;
import com.hwannee.ieum.orders.web.dto.OrderResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

@Component
public class OrderCreateRetrier {

    private final OrderService orderService;
    private final OrderProperties.Retry retry;
    private final Counter success;
    private final Counter conflict;
    private final Counter exhausted;
    private final Counter deadlock;
    private final DistributionSummary attemptsUsed;

    public OrderCreateRetrier(OrderService orderService, OrderProperties properties, MeterRegistry registry) {
        this.orderService = orderService;
        this.retry = properties.retry();
        this.success = outcome(registry, "success");
        this.conflict = outcome(registry, "conflict");
        this.exhausted = outcome(registry, "exhausted");
        this.deadlock = outcome(registry, "deadlock");
        this.attemptsUsed = DistributionSummary.builder("order.create.attempts.used")
                .serviceLevelObjectives(IntStream.rangeClosed(1, retry.maxAttempts()).asDoubleStream().toArray())
                .register(registry);
    }

    public OrderResponse create(AuthenticatedUser user, CreateOrderRequest request, String idempotencyKey) {
        for (int attempt = 1; ; attempt++) {
            try {
                OrderResponse response = orderService.create(user, request, idempotencyKey);
                success.increment();
                attemptsUsed.record(attempt);
                return response;
            } catch (OptimisticLockingFailureException | CannotAcquireLockException e) {
                (e instanceof CannotAcquireLockException ? deadlock : conflict).increment();
                if (attempt >= retry.maxAttempts()) {
                    exhausted.increment();
                    throw e;
                }
                backoff(attempt);
            }
        }
    }

    private void backoff(int attempt) {
        long upperBound = retry.backoff().toNanos() * attempt;
        if (upperBound <= 0) {
            return;
        }
        try {
            TimeUnit.NANOSECONDS.sleep(ThreadLocalRandom.current().nextLong(upperBound + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Counter outcome(MeterRegistry registry, String outcome) {
        return Counter.builder("order.create.attempts").tag("outcome", outcome).register(registry);
    }
}
