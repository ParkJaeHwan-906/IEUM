package com.hwannee.ieum.orders.service;

import com.hwannee.ieum.auth.verify.principal.AuthenticatedUser;
import com.hwannee.ieum.orders.exception.OrderException;
import com.hwannee.ieum.orders.stock.RedisStockDeduction;
import com.hwannee.ieum.orders.stock.RedisStockDeduction.Reservation;
import com.hwannee.ieum.orders.web.dto.CreateOrderRequest;
import com.hwannee.ieum.orders.web.dto.OrderResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@ConditionalOnProperty(name = "ieum.stock.strategy", havingValue = "redis")
public class RedisOrderCreator implements OrderCreator {

    private static final Logger log = LoggerFactory.getLogger(RedisOrderCreator.class);

    private final ItemSaleCache saleCache;
    private final RedisStockDeduction ledger;
    private final OrderService orderService;

    public RedisOrderCreator(ItemSaleCache saleCache, RedisStockDeduction ledger, OrderService orderService) {
        this.saleCache = saleCache;
        this.ledger = ledger;
        this.orderService = orderService;
    }

    @Override
    public OrderResponse create(AuthenticatedUser user, CreateOrderRequest request, String idempotencyKey) {
        ItemSaleCache.ItemSale sale = saleCache.get(request.itemUid());
        if (!sale.onSaleAt(LocalDateTime.now())) {
            throw new OrderException.ItemNotOnSale();
        }

        Reservation reservation = ledger.reserve(sale.itemId(), user.uid(), idempotencyKey, request.quantity());
        switch (reservation.outcome()) {
            case REPLAY -> {
                return orderService.replay(user, request.itemUid(), idempotencyKey);
            }
            case IN_FLIGHT -> throw new OrderException.IdempotencyInProgress();
            case DUPLICATE -> throw new OrderException.DuplicateActiveOrder();
            case SOLD_OUT -> throw new OrderException.InsufficientStock();
            case RESERVED -> {
            }
        }

        OrderResponse response;
        try {
            response = orderService.place(user, sale.itemId(), request.quantity(), idempotencyKey);
        } catch (DataIntegrityViolationException e) {
            ledger.compensate(sale.itemId(), user.uid(), idempotencyKey, request.quantity());
            return orderService.replay(user, request.itemUid(), idempotencyKey);
        } catch (RuntimeException e) {
            ledger.compensate(sale.itemId(), user.uid(), idempotencyKey, request.quantity());
            throw e;
        }

        try {
            ledger.confirm(sale.itemId(), user.uid(), idempotencyKey, response.orderId());
        } catch (RuntimeException e) {
            log.warn("멱등키 확정 실패, PENDING TTL 만료 후 DB 로 재생: orderId={}", response.orderId(), e);
        }
        return response;
    }
}
