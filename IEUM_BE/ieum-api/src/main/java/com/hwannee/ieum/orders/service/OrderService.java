package com.hwannee.ieum.orders.service;

import com.hwannee.ieum.auth.verify.principal.AuthenticatedUser;
import com.hwannee.ieum.orders.config.OrderProperties;
import com.hwannee.ieum.orders.domain.OrderState;
import com.hwannee.ieum.orders.domain.UsersOrders;
import com.hwannee.ieum.orders.exception.OrderException;
import com.hwannee.ieum.orders.expiry.ExpiryIndex;
import com.hwannee.ieum.orders.repository.UsersOrdersRepository;
import com.hwannee.ieum.orders.stock.StockDeductionStrategy;
import com.hwannee.ieum.orders.web.dto.CreateOrderRequest;
import com.hwannee.ieum.orders.web.dto.OrderResponse;
import com.hwannee.ieum.stores.domain.StoresItems;
import com.hwannee.ieum.stores.repository.StoresItemsRepository;
import com.hwannee.ieum.users.domain.UsersAccount;
import com.hwannee.ieum.users.repository.UsersAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class OrderService {

    private final UsersOrdersRepository orders;
    private final StoresItemsRepository items;
    private final UsersAccountRepository accounts;
    private final StockDeductionStrategy stock;
    private final ExpiryIndex expiryIndex;
    private final OrderProperties properties;
    private final PickupCodeIssuer pickupCodes;

    public OrderService(UsersOrdersRepository orders, StoresItemsRepository items,
                        UsersAccountRepository accounts, StockDeductionStrategy stock,
                        ExpiryIndex expiryIndex, OrderProperties properties, PickupCodeIssuer pickupCodes) {
        this.orders = orders;
        this.items = items;
        this.accounts = accounts;
        this.stock = stock;
        this.expiryIndex = expiryIndex;
        this.properties = properties;
        this.pickupCodes = pickupCodes;
    }

    @Transactional
    public OrderResponse create(AuthenticatedUser user, CreateOrderRequest request, String idempotencyKey) {
        StoresItems item = (stock.locksItemRow()
                ? items.findByUidForUpdate(request.itemUid())
                : items.findByUid(request.itemUid()))
                .orElseThrow(OrderException.ItemNotFound::new);
        UsersAccount account = accounts.findByUid(user.uid()).orElseThrow(OrderException.AccountNotFound::new);

        Optional<UsersOrders> previous = orders.findByUsersAccount_IdAndIdempotencyKey(account.getId(), idempotencyKey);
        if (previous.isPresent()) {
            return replayOf(previous.get(), request.itemUid());
        }

        if (item.getStore().isShutdown() || LocalDateTime.now().isAfter(item.getLastOrderTime())) {
            throw new OrderException.ItemNotOnSale();
        }

        if (orders.existsByAccountAndItemInStates(account.getId(), item.getId(), OrderState.ACTIVE)) {
            throw new OrderException.DuplicateActiveOrder();
        }

        stock.deduct(item.getId(), request.quantity());
        UsersOrders order = orders.save(new UsersOrders(account, item, request.quantity(), idempotencyKey));
        return OrderResponse.from(order);
    }

    @Transactional
    public OrderResponse place(AuthenticatedUser user, Long itemId, int quantity, String idempotencyKey) {
        UsersAccount account = accounts.findByUid(user.uid()).orElseThrow(OrderException.AccountNotFound::new);
        StoresItems item = items.findById(itemId).orElseThrow(OrderException.ItemNotFound::new);
        UsersOrders order = orders.save(new UsersOrders(account, item, quantity, idempotencyKey));
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public OrderResponse replay(AuthenticatedUser user, String itemUid, String idempotencyKey) {
        UsersOrders previous = orders.findByUsersAccount_UidAndIdempotencyKey(user.uid(), idempotencyKey)
                .orElseThrow(OrderException.OrderNotFound::new);
        return replayOf(previous, itemUid);
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> findMine(AuthenticatedUser user) {
        return orders.findAllByUsersAccount_UidOrderByIdDesc(user.uid()).stream()
                .map(OrderResponse::from)
                .toList();
    }

    @Transactional
    public OrderResponse cancel(AuthenticatedUser user, Long orderId) {
        UsersOrders order = ownedByConsumer(user, orderId);
        order.cancel();
        stock.restore(order);
        expiryIndex.remove(order.getId());
        return OrderResponse.from(order);
    }

    @Transactional
    public OrderResponse approve(AuthenticatedUser owner, Long orderId) {
        UsersOrders order = ownedByStoreOwner(owner, orderId);
        order.approve();
        return OrderResponse.forOwner(order);
    }

    @Transactional
    public OrderResponse readyForPickup(AuthenticatedUser owner, Long orderId) {
        UsersOrders order = ownedByStoreOwner(owner, orderId);
        order.readyForPickup(pickupCodes.issue());
        expiryIndex.register(order.getId(), order.getReadyAt().plus(properties.pickupTtl()));
        return OrderResponse.forOwner(order);
    }

    @Transactional
    public OrderResponse pickUp(AuthenticatedUser owner, Long orderId, String pickupCode) {
        UsersOrders order = ownedByStoreOwner(owner, orderId);
        if (order.getOrderState() == OrderState.READY_FOR_PICKUP && !order.matchesPickupCode(pickupCode)) {
            throw new OrderException.PickupCodeMismatch();
        }
        order.pickUp();
        stock.settle(order);
        expiryIndex.remove(order.getId());
        return OrderResponse.forOwner(order);
    }

    @Transactional
    public OrderResponse reject(AuthenticatedUser owner, Long orderId) {
        UsersOrders order = ownedByStoreOwner(owner, orderId);
        order.reject();
        stock.restore(order);
        return OrderResponse.forOwner(order);
    }

    @Transactional
    public void expire(Long orderId) {
        UsersOrders order = orders.findById(orderId).orElseThrow(OrderException.OrderNotFound::new);
        order.expire();
        stock.restore(order);
    }

    @Transactional
    public void cancelUnapproved(Long orderId) {
        UsersOrders order = orders.findById(orderId).orElseThrow(OrderException.OrderNotFound::new);
        order.cancelUnapproved();
        stock.restore(order);
    }

    private UsersOrders ownedByConsumer(AuthenticatedUser consumer, Long orderId) {
        return orders.findByIdAndUsersAccount_Uid(orderId, consumer.uid())
                .orElseThrow(OrderException.OrderNotFound::new);
    }

    private UsersOrders ownedByStoreOwner(AuthenticatedUser owner, Long orderId) {
        return orders.findByIdAndStoreOwnerUid(orderId, owner.uid())
                .orElseThrow(OrderException.OrderNotFound::new);
    }

    private static OrderResponse replayOf(UsersOrders previous, String itemUid) {
        if (!previous.getStoresItem().getUid().equals(itemUid)) {
            throw new OrderException.IdempotencyKeyReused();
        }
        return OrderResponse.from(previous);
    }
}
