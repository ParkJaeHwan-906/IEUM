package com.hwannee.ieum.orders.service;

import com.hwannee.ieum.auth.verify.principal.AuthenticatedUser;
import com.hwannee.ieum.orders.domain.OrderState;
import com.hwannee.ieum.orders.domain.UsersOrders;
import com.hwannee.ieum.orders.exception.OrderException;
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

@Service
public class OrderService {

    private final UsersOrdersRepository orders;
    private final StoresItemsRepository items;
    private final UsersAccountRepository accounts;
    private final StockDeductionStrategy stock;

    public OrderService(UsersOrdersRepository orders, StoresItemsRepository items,
                        UsersAccountRepository accounts, StockDeductionStrategy stock) {
        this.orders = orders;
        this.items = items;
        this.accounts = accounts;
        this.stock = stock;
    }

    @Transactional
    public OrderResponse create(AuthenticatedUser user, CreateOrderRequest request, String idempotencyKey) {
        UsersAccount account = accounts.findByUid(user.uid()).orElseThrow(OrderException.AccountNotFound::new);
        StoresItems item = items.findByUid(request.itemUid()).orElseThrow(OrderException.ItemNotFound::new);

        if (item.getStore().isShutdown() || LocalDateTime.now().isAfter(item.getLastOrderTime())) {
            throw new OrderException.ItemNotOnSale();
        }

        if (orders.existsByAccountAndItemInStates(account.getId(), item.getId(), OrderState.ACTIVE)) {
            throw new OrderException.DuplicateActiveOrder();
        }

        stock.deduct(item.getId(), request.quantity());
        UsersOrders order = orders.save(new UsersOrders(account, item, request.quantity()));
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> findMine(AuthenticatedUser user) {
        return orders.findAllByUsersAccount_UidOrderByIdDesc(user.uid()).stream()
                .map(OrderResponse::from)
                .toList();
    }

    // 소유권 검사: 조회 조건에 uid 를 넣어 타인의 주문은 존재하지 않는 것으로 취급한다 (404)
    @Transactional
    public OrderResponse cancel(AuthenticatedUser user, Long orderId) {
        UsersOrders order = orders.findByIdAndUsersAccount_Uid(orderId, user.uid())
                .orElseThrow(OrderException.OrderNotFound::new);
        order.cancel();
        stock.restore(order.getStoresItem().getId(), order.getQuantity());
        // TODO(2.2 만료): READY_FOR_PICKUP 에서 취소되면 Sorted Set 에서 ZREM
        return OrderResponse.from(order);
    }

    @Transactional
    public OrderResponse approve(AuthenticatedUser owner, Long orderId) {
        UsersOrders order = ownedByStoreOwner(owner, orderId);
        order.approve();
        return OrderResponse.from(order);
    }

    @Transactional
    public OrderResponse readyForPickup(AuthenticatedUser owner, Long orderId) {
        UsersOrders order = ownedByStoreOwner(owner, orderId);
        order.readyForPickup();
        // TODO(2.2 만료): ZADD orders:expiry {orderId} score = readyAt + OrderProperties.pickupTtl
        // TODO(2.2 픽업 코드): 발급 후 응답에 포함
        return OrderResponse.from(order);
    }

    @Transactional
    public OrderResponse pickUp(AuthenticatedUser owner, Long orderId) {
        UsersOrders order = ownedByStoreOwner(owner, orderId);
        // TODO(2.2 픽업 코드): 요청의 코드와 대조
        order.pickUp();
        // TODO(2.2 만료): ZREM orders:expiry {orderId}
        return OrderResponse.from(order);
    }

    // TODO(2.2 만료): Expiry Worker 가 호출할 expire(orderId). expire() 전이 + stock.restore, 복구는 주문당 1회 보장
    // TODO(2.2 점주 취소): 점주가 PENDING 을 거절하는 경로. cancel 과 같은 전이지만 소유권 검사가 다르다

    // TODO(1.5 소유권 규약): 같은 패턴이 StoresItems·ItemsReviews 에도 반복되므로 규약을 정한 뒤 공통 위치로 옮긴다.
    //   타인의 주문에 403 을 줄지 404 로 숨길지도 그때 결정. 소비자 쪽(cancel)은 404 로 숨기고 있다
    private UsersOrders ownedByStoreOwner(AuthenticatedUser owner, Long orderId) {
        UsersOrders order = orders.findById(orderId).orElseThrow(OrderException.OrderNotFound::new);
        String storeOwnerUid = order.getStoresItem().getStore().getUsersAccount().getUid();
        if (!storeOwnerUid.equals(owner.uid())) {
            throw new OrderException.NotStoreOwner();
        }
        return order;
    }
}
