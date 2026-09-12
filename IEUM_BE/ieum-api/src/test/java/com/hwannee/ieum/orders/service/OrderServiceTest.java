package com.hwannee.ieum.orders.service;

import com.hwannee.ieum.auth.verify.principal.AuthenticatedUser;
import com.hwannee.ieum.orders.domain.OrderState;
import com.hwannee.ieum.orders.domain.UsersOrders;
import com.hwannee.ieum.orders.exception.OrderException;
import com.hwannee.ieum.orders.repository.UsersOrdersRepository;
import com.hwannee.ieum.orders.stock.StockDeductionStrategy;
import com.hwannee.ieum.orders.web.dto.CreateOrderRequest;
import com.hwannee.ieum.orders.web.dto.OrderResponse;
import com.hwannee.ieum.stores.domain.StoreType;
import com.hwannee.ieum.stores.domain.Stores;
import com.hwannee.ieum.stores.domain.StoresItems;
import com.hwannee.ieum.stores.repository.StoresItemsRepository;
import com.hwannee.ieum.users.domain.UserType;
import com.hwannee.ieum.users.domain.UsersAccount;
import com.hwannee.ieum.users.repository.UsersAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final String USER_UID = "consumer-uid";
    private static final String ITEM_UID = "item-uid";
    private static final long ACCOUNT_ID = 1L;
    private static final long ITEM_ID = 10L;

    @Mock
    UsersOrdersRepository orders;

    @Mock
    StoresItemsRepository items;

    @Mock
    UsersAccountRepository accounts;

    @Mock
    StockDeductionStrategy stock;

    @InjectMocks
    OrderService service;

    AuthenticatedUser user;
    UsersAccount account;
    Stores store;

    @BeforeEach
    void setUp() {
        user = new AuthenticatedUser(USER_UID, UserType.CONSUMER, "nick");
        account = new UsersAccount(null, UserType.CONSUMER, USER_UID, "nick", "encoded");
        ReflectionTestUtils.setField(account, "id", ACCOUNT_ID);
        store = new Stores(account, "store-uid", "가게", StoreType.CAFE, LocalTime.of(9, 0), LocalTime.of(18, 0));
    }

    @Test
    void 계정이_없으면_AccountNotFound() {
        given(accounts.findByUid(USER_UID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(user, request(1), null))
                .isInstanceOf(OrderException.AccountNotFound.class);
        thenNothingDeductedOrSaved();
    }

    @Test
    void 상품이_없으면_ItemNotFound() {
        given(accounts.findByUid(USER_UID)).willReturn(Optional.of(account));
        given(items.findByUid(ITEM_UID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(user, request(1), null))
                .isInstanceOf(OrderException.ItemNotFound.class);
        thenNothingDeductedOrSaved();
    }

    @Test
    void 마감_시각이_지난_상품은_ItemNotOnSale() {
        givenAccountAndItem(item(LocalDateTime.now().minusMinutes(1)));

        assertThatThrownBy(() -> service.create(user, request(1), null))
                .isInstanceOf(OrderException.ItemNotOnSale.class);
        then(orders).should(never()).existsByAccountAndItemInStates(anyLong(), anyLong(), any());
        thenNothingDeductedOrSaved();
    }

    @Test
    void 영업_종료된_가게의_상품은_ItemNotOnSale() {
        store.shutdown();
        givenAccountAndItem(item(LocalDateTime.now().plusHours(1)));

        assertThatThrownBy(() -> service.create(user, request(1), null))
                .isInstanceOf(OrderException.ItemNotOnSale.class);
        then(orders).should(never()).existsByAccountAndItemInStates(anyLong(), anyLong(), any());
        thenNothingDeductedOrSaved();
    }

    @Test
    void 활성_예약이_있으면_DuplicateActiveOrder() {
        givenAccountAndItem(item(LocalDateTime.now().plusHours(1)));
        given(orders.existsByAccountAndItemInStates(ACCOUNT_ID, ITEM_ID, OrderState.ACTIVE)).willReturn(true);

        assertThatThrownBy(() -> service.create(user, request(1), null))
                .isInstanceOf(OrderException.DuplicateActiveOrder.class);
        thenNothingDeductedOrSaved();
    }

    @Test
    void 재고가_부족하면_InsufficientStock_이고_저장하지_않는다() {
        givenAccountAndItem(item(LocalDateTime.now().plusHours(1)));
        given(orders.existsByAccountAndItemInStates(ACCOUNT_ID, ITEM_ID, OrderState.ACTIVE)).willReturn(false);
        willThrow(new OrderException.InsufficientStock(0))
                .given(stock).deduct(ITEM_ID, 3);

        assertThatThrownBy(() -> service.create(user, request(3), null))
                .isInstanceOf(OrderException.InsufficientStock.class);
        then(orders).should(never()).save(any());
    }

    @Test
    void 조건을_모두_통과하면_차감_후_PENDING_으로_저장한다() {
        StoresItems item = item(LocalDateTime.now().plusHours(1));
        givenAccountAndItem(item);
        given(orders.existsByAccountAndItemInStates(ACCOUNT_ID, ITEM_ID, OrderState.ACTIVE)).willReturn(false);
        given(orders.save(any(UsersOrders.class))).willAnswer(invocation -> {
            UsersOrders saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 100L);
            return saved;
        });

        OrderResponse response = service.create(user, request(2), null);

        then(stock).should().deduct(ITEM_ID, 2);
        assertThat(response.orderId()).isEqualTo(100L);
        assertThat(response.itemUid()).isEqualTo(ITEM_UID);
        assertThat(response.quantity()).isEqualTo(2);
        assertThat(response.orderPrice()).isEqualTo(item.getSalePrice());
        assertThat(response.state()).isEqualTo(OrderState.PENDING);
    }

    private void givenAccountAndItem(StoresItems item) {
        given(accounts.findByUid(USER_UID)).willReturn(Optional.of(account));
        given(items.findByUid(ITEM_UID)).willReturn(Optional.of(item));
    }

    private void thenNothingDeductedOrSaved() {
        then(stock).should(never()).deduct(anyLong(), anyInt());
        then(orders).should(never()).save(any());
    }

    private StoresItems item(LocalDateTime lastOrderTime) {
        StoresItems item = new StoresItems(store, ITEM_UID, null, "빵", 5000, 3000, 100, lastOrderTime);
        ReflectionTestUtils.setField(item, "id", ITEM_ID);
        return item;
    }

    private CreateOrderRequest request(int quantity) {
        return new CreateOrderRequest(ITEM_UID, quantity);
    }
}
