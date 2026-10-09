package com.hwannee.ieum.orders.service;

import com.hwannee.ieum.auth.verify.principal.AuthenticatedUser;
import com.hwannee.ieum.orders.domain.InvalidOrderStateException;
import com.hwannee.ieum.orders.domain.OrderState;
import com.hwannee.ieum.orders.domain.UsersOrders;
import com.hwannee.ieum.orders.config.OrderProperties;
import com.hwannee.ieum.orders.exception.OrderException;
import com.hwannee.ieum.orders.expiry.ExpiryIndex;
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
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
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
    private static final String KEY = "idem-key";

    @Mock
    UsersOrdersRepository orders;

    @Mock
    StoresItemsRepository items;

    @Mock
    UsersAccountRepository accounts;

    @Mock
    StockDeductionStrategy stock;

    @Mock
    ExpiryIndex expiryIndex;

    @Mock
    PickupCodeIssuer pickupCodes;

    @Spy
    OrderProperties properties = new OrderProperties(Duration.ofMinutes(15), new OrderProperties.Retry(3, Duration.ofMillis(10)),
            new OrderProperties.Idempotency(Duration.ofDays(1), Duration.ofSeconds(30)), Duration.ofSeconds(5),
                Duration.ofMinutes(5));

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
        given(items.findByUid(ITEM_UID)).willReturn(Optional.of(item(LocalDateTime.now().plusHours(1))));
        given(accounts.findByUid(USER_UID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(user, request(1), null))
                .isInstanceOf(OrderException.AccountNotFound.class);
        thenNothingDeductedOrSaved();
    }

    @Test
    void 상품이_없으면_ItemNotFound() {
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

    @Test
    void 같은_멱등키의_주문이_있으면_차감하지_않고_그_주문을_돌려준다() {
        StoresItems item = item(LocalDateTime.now().plusHours(1));
        givenAccountAndItem(item);
        UsersOrders previous = savedOrder(item, 2, 200L);
        given(orders.findByUsersAccount_IdAndIdempotencyKey(ACCOUNT_ID, KEY)).willReturn(Optional.of(previous));

        OrderResponse response = service.create(user, request(2), KEY);

        assertThat(response.orderId()).isEqualTo(200L);
        thenNothingDeductedOrSaved();
    }

    @Test
    void 같은_멱등키를_다른_상품에_쓰면_IdempotencyKeyReused() {
        givenAccountAndItem(item(LocalDateTime.now().plusHours(1)));
        StoresItems other = new StoresItems(store, "other-item", null, "케이크", 5000, 3000, 100,
                LocalDateTime.now().plusHours(1));
        given(orders.findByUsersAccount_IdAndIdempotencyKey(ACCOUNT_ID, KEY))
                .willReturn(Optional.of(savedOrder(other, 1, 300L)));

        assertThatThrownBy(() -> service.create(user, request(1), KEY))
                .isInstanceOf(OrderException.IdempotencyKeyReused.class);
        thenNothingDeductedOrSaved();
    }

    @Test
    void 행을_잠그는_전략이면_상품을_FOR_UPDATE_로_먼저_조회한다() {
        StoresItems item = item(LocalDateTime.now().plusHours(1));
        given(stock.locksItemRow()).willReturn(true);
        given(items.findByUidForUpdate(ITEM_UID)).willReturn(Optional.of(item));
        given(accounts.findByUid(USER_UID)).willReturn(Optional.of(account));
        given(orders.save(any(UsersOrders.class))).willAnswer(invocation -> invocation.getArgument(0));

        service.create(user, request(1), KEY);

        then(items).should(never()).findByUid(any());
        then(stock).should().deduct(ITEM_ID, 1);
    }

    @Test
    void place_는_재고를_건드리지_않고_주문만_저장한다() {
        StoresItems item = item(LocalDateTime.now().plusHours(1));
        given(accounts.findByUid(USER_UID)).willReturn(Optional.of(account));
        given(items.findById(ITEM_ID)).willReturn(Optional.of(item));
        given(orders.save(any(UsersOrders.class))).willAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = service.place(user, ITEM_ID, 2, KEY);

        assertThat(response.quantity()).isEqualTo(2);
        assertThat(response.state()).isEqualTo(OrderState.PENDING);
        then(stock).should(never()).deduct(anyLong(), anyInt());
    }

    @Test
    void 취소하면_주문_단위로_재고를_복구한다() {
        UsersOrders order = savedOrder(item(LocalDateTime.now().plusHours(1)), 1, 400L);
        given(orders.findByIdAndUsersAccount_Uid(400L, USER_UID)).willReturn(Optional.of(order));

        service.cancel(user, 400L);

        assertThat(order.getOrderState()).isEqualTo(OrderState.CANCELED);
        then(stock).should().restore(order);
        then(expiryIndex).should().remove(400L);
    }

    @Test
    void 픽업하면_재고는_복구하지_않고_settle_만_호출한다() {
        UsersOrders order = savedOrder(item(LocalDateTime.now().plusHours(1)), 1, 500L);
        order.approve();
        order.readyForPickup("123456");
        given(orders.findById(500L)).willReturn(Optional.of(order));
        AuthenticatedUser owner = new AuthenticatedUser(USER_UID, UserType.BUSINESS_OWNER, "nick");

        service.pickUp(owner, 500L, "123456");

        then(stock).should().settle(order);
        then(stock).should(never()).restore(any());
    }

    @Test
    void 미승인_자동_취소는_PENDING_을_취소하고_재고를_복구한다() {
        UsersOrders order = savedOrder(item(LocalDateTime.now().plusHours(1)), 1, 600L);
        given(orders.findById(600L)).willReturn(Optional.of(order));

        service.cancelUnapproved(600L);

        assertThat(order.getOrderState()).isEqualTo(OrderState.CANCELED);
        then(stock).should().restore(order);
    }

    @Test
    void 이미_승인된_주문은_자동_취소하지_않는다() {
        UsersOrders order = savedOrder(item(LocalDateTime.now().plusHours(1)), 1, 700L);
        order.approve();
        given(orders.findById(700L)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancelUnapproved(700L))
                .isInstanceOf(InvalidOrderStateException.class);
        assertThat(order.getOrderState()).isEqualTo(OrderState.APPROVED);
        then(stock).should(never()).restore(any());
    }

    @Test
    void 준비_완료하면_픽업_코드를_발급하고_점주_응답에는_싣지_않는다() {
        UsersOrders order = savedOrder(item(LocalDateTime.now().plusHours(1)), 1, 800L);
        order.approve();
        given(orders.findById(800L)).willReturn(Optional.of(order));
        given(pickupCodes.issue()).willReturn("482913");

        OrderResponse ownerView = service.readyForPickup(owner(), 800L);

        assertThat(order.getPickupCode()).isEqualTo("482913");
        assertThat(ownerView.pickupCode()).isNull();
        assertThat(OrderResponse.from(order).pickupCode()).isEqualTo("482913");
    }

    @Test
    void 픽업_코드가_틀리면_PickupCodeMismatch_이고_상태는_그대로다() {
        UsersOrders order = readyOrder(900L, "482913");

        assertThatThrownBy(() -> service.pickUp(owner(), 900L, "000000"))
                .isInstanceOf(OrderException.PickupCodeMismatch.class);
        assertThat(order.getOrderState()).isEqualTo(OrderState.READY_FOR_PICKUP);
        then(stock).should(never()).settle(any());
        then(expiryIndex).should(never()).remove(anyLong());
    }

    @Test
    void 준비_전_주문의_픽업은_코드와_무관하게_상태_예외다() {
        UsersOrders order = savedOrder(item(LocalDateTime.now().plusHours(1)), 1, 950L);
        given(orders.findById(950L)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> service.pickUp(owner(), 950L, "000000"))
                .isInstanceOf(InvalidOrderStateException.class);
    }

    @Test
    void 점주가_PENDING_을_거절하면_취소되고_재고를_복구한다() {
        UsersOrders order = savedOrder(item(LocalDateTime.now().plusHours(1)), 1, 1000L);
        given(orders.findById(1000L)).willReturn(Optional.of(order));

        OrderResponse response = service.reject(owner(), 1000L);

        assertThat(response.state()).isEqualTo(OrderState.CANCELED);
        then(stock).should().restore(order);
    }

    @Test
    void 승인된_주문은_거절할_수_없다() {
        UsersOrders order = savedOrder(item(LocalDateTime.now().plusHours(1)), 1, 1100L);
        order.approve();
        given(orders.findById(1100L)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> service.reject(owner(), 1100L))
                .isInstanceOf(InvalidOrderStateException.class);
        then(stock).should(never()).restore(any());
    }

    @Test
    void 다른_가게의_점주는_거절할_수_없다() {
        UsersOrders order = savedOrder(item(LocalDateTime.now().plusHours(1)), 1, 1200L);
        given(orders.findById(1200L)).willReturn(Optional.of(order));
        AuthenticatedUser stranger = new AuthenticatedUser("other-owner", UserType.BUSINESS_OWNER, "nick");

        assertThatThrownBy(() -> service.reject(stranger, 1200L))
                .isInstanceOf(OrderException.NotStoreOwner.class);
        assertThat(order.getOrderState()).isEqualTo(OrderState.PENDING);
    }

    private AuthenticatedUser owner() {
        return new AuthenticatedUser(USER_UID, UserType.BUSINESS_OWNER, "nick");
    }

    private UsersOrders readyOrder(long id, String code) {
        UsersOrders order = savedOrder(item(LocalDateTime.now().plusHours(1)), 1, id);
        order.approve();
        order.readyForPickup(code);
        given(orders.findById(id)).willReturn(Optional.of(order));
        return order;
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

    private UsersOrders savedOrder(StoresItems item, int quantity, long id) {
        UsersOrders order = new UsersOrders(account, item, quantity, KEY);
        ReflectionTestUtils.setField(order, "id", id);
        return order;
    }

    private CreateOrderRequest request(int quantity) {
        return new CreateOrderRequest(ITEM_UID, quantity);
    }
}
