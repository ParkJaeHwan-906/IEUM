package com.hwannee.ieum.stores.service;

import com.hwannee.ieum.auth.verify.principal.AuthenticatedUser;
import com.hwannee.ieum.orders.domain.OrderState;
import com.hwannee.ieum.orders.domain.UsersOrders;
import com.hwannee.ieum.orders.repository.UsersOrdersRepository;
import com.hwannee.ieum.orders.service.ItemSaleCache;
import com.hwannee.ieum.orders.stock.StockDeductionStrategy;
import com.hwannee.ieum.orders.web.dto.OrderResponse;
import com.hwannee.ieum.stores.domain.StoreType;
import com.hwannee.ieum.stores.domain.Stores;
import com.hwannee.ieum.stores.domain.StoresItems;
import com.hwannee.ieum.stores.exception.StoreException;
import com.hwannee.ieum.stores.repository.StoresItemsRepository;
import com.hwannee.ieum.stores.repository.StoresRepository;
import com.hwannee.ieum.stores.web.dto.CreateItemRequest;
import com.hwannee.ieum.stores.web.dto.ItemResponse;
import com.hwannee.ieum.users.domain.UserType;
import com.hwannee.ieum.users.domain.UsersAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class StoreItemServiceTest {

    private static final String OWNER_UID = "owner-uid";
    private static final String STORE_UID = "store-uid";
    private static final String ITEM_UID = "item-uid";
    private static final long ITEM_ID = 10L;

    @Mock
    StoresItemsRepository items;

    @Mock
    StoresRepository stores;

    @Mock
    StoreService storeService;

    @Mock
    StockDeductionStrategy stock;

    @Mock
    UsersOrdersRepository orders;

    @Mock
    ObjectProvider<ItemSaleCache> saleCache;

    @InjectMocks
    StoreItemService service;

    AuthenticatedUser owner;
    AuthenticatedUser stranger;
    UsersAccount account;
    Stores store;

    @BeforeEach
    void setUp() {
        owner = new AuthenticatedUser(OWNER_UID, UserType.BUSINESS_OWNER, "owner");
        stranger = new AuthenticatedUser("other-owner", UserType.BUSINESS_OWNER, "other");
        account = new UsersAccount(null, UserType.BUSINESS_OWNER, OWNER_UID, "owner", "encoded");
        store = new Stores(account, STORE_UID, "가게", StoreType.CAFE, LocalTime.of(9, 0), LocalTime.of(18, 0));
    }

    @Test
    void 다른_점주의_가게에는_상품을_등록할_수_없다() {
        given(storeService.ownedBy(stranger, STORE_UID)).willThrow(new StoreException.StoreNotFound());
        CreateItemRequest request = new CreateItemRequest("빵", 5000, 3000, 10, LocalDateTime.now().plusHours(1), null);

        assertThatThrownBy(() -> service.create(stranger, STORE_UID, request))
                .isInstanceOf(StoreException.StoreNotFound.class);
        then(items).should(never()).save(any());
    }

    @Test
    void 다른_점주의_상품은_조정_종료_조회_모두_ItemNotFound() {
        given(items.findOwnedByUidForUpdate(ITEM_UID, STORE_UID, "other-owner")).willReturn(Optional.empty());
        given(items.findOwnedByUid(ITEM_UID, STORE_UID, "other-owner")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.adjustQuantity(stranger, STORE_UID, ITEM_UID, 50))
                .isInstanceOf(StoreException.ItemNotFound.class);
        assertThatThrownBy(() -> service.close(stranger, STORE_UID, ITEM_UID))
                .isInstanceOf(StoreException.ItemNotFound.class);
        assertThatThrownBy(() -> service.findOrders(stranger, STORE_UID, ITEM_UID, null))
                .isInstanceOf(StoreException.ItemNotFound.class);
        then(stock).should(never()).adjust(any(), anyInt());
    }

    @Test
    void 재고_조정은_잠근_상품을_전략에_넘긴다() {
        StoresItems item = item(LocalDateTime.now().plusHours(1));
        given(items.findOwnedByUidForUpdate(ITEM_UID, STORE_UID, OWNER_UID)).willReturn(Optional.of(item));
        given(stock.adjust(item, 120)).willAnswer(invocation -> {
            item.changeInitialQuantity(120);
            return true;
        });

        ItemResponse response = service.adjustQuantity(owner, STORE_UID, ITEM_UID, 120);

        assertThat(response.initialQuantity()).isEqualTo(120);
        assertThat(response.remainingQuantity()).isEqualTo(120);
        then(items).should(never()).findOwnedByUid(any(), any(), any());
    }

    @Test
    void 전략이_거절하면_QuantityBelowHeld() {
        StoresItems item = item(LocalDateTime.now().plusHours(1));
        given(items.findOwnedByUidForUpdate(ITEM_UID, STORE_UID, OWNER_UID)).willReturn(Optional.of(item));
        given(stock.adjust(item, 3)).willReturn(false);

        assertThatThrownBy(() -> service.adjustQuantity(owner, STORE_UID, ITEM_UID, 3))
                .isInstanceOf(StoreException.QuantityBelowHeld.class)
                .hasMessage("이미 예약된 수량보다 적게 줄일 수 없습니다.");
    }

    @Test
    void 판매_종료는_마감_시각을_지금으로_당기고_캐시를_비운다() {
        StoresItems item = item(LocalDateTime.now().plusHours(1));
        given(items.findOwnedByUidForUpdate(ITEM_UID, STORE_UID, OWNER_UID)).willReturn(Optional.of(item));

        ItemResponse response = service.close(owner, STORE_UID, ITEM_UID);

        assertThat(response.lastOrderTime()).isCloseTo(LocalDateTime.now(), within(5, ChronoUnit.SECONDS));
        assertThat(item.isSaleClosedAt(LocalDateTime.now().plusSeconds(1))).isTrue();
        then(saleCache).should().ifAvailable(any());
    }

    @Test
    void 이미_마감된_상품은_ItemSaleClosed() {
        LocalDateTime past = LocalDateTime.now().minusMinutes(1);
        StoresItems item = item(past);
        given(items.findOwnedByUidForUpdate(ITEM_UID, STORE_UID, OWNER_UID)).willReturn(Optional.of(item));

        assertThatThrownBy(() -> service.close(owner, STORE_UID, ITEM_UID))
                .isInstanceOf(StoreException.ItemSaleClosed.class)
                .hasMessage("이미 판매가 종료된 상품입니다.");
        assertThat(item.getLastOrderTime()).isEqualTo(past);
    }

    @Test
    void 상품별_예약_현황은_상태가_없으면_전부_있으면_그_상태만_픽업_코드_없이() {
        StoresItems item = item(LocalDateTime.now().plusHours(1));
        given(items.findOwnedByUid(ITEM_UID, STORE_UID, OWNER_UID)).willReturn(Optional.of(item));
        UsersOrders ready = order(item, 2L);
        ready.approve();
        ready.readyForPickup("123456");
        UsersOrders pending = order(item, 1L);
        given(orders.findAllByStoresItem_IdOrderByIdDesc(ITEM_ID)).willReturn(List.of(ready, pending));
        given(orders.findAllByStoresItem_IdAndOrderStateOrderByIdDesc(ITEM_ID, OrderState.PENDING))
                .willReturn(List.of(pending));

        List<OrderResponse> all = service.findOrders(owner, STORE_UID, ITEM_UID, null);
        List<OrderResponse> onlyPending = service.findOrders(owner, STORE_UID, ITEM_UID, OrderState.PENDING);

        assertThat(all).extracting(OrderResponse::orderId).containsExactly(2L, 1L);
        assertThat(all).extracting(OrderResponse::pickupCode).containsOnlyNulls();
        assertThat(onlyPending).extracting(OrderResponse::state).containsExactly(OrderState.PENDING);
    }

    private StoresItems item(LocalDateTime lastOrderTime) {
        StoresItems item = new StoresItems(store, ITEM_UID, null, "빵", 5000, 3000, 100, lastOrderTime);
        ReflectionTestUtils.setField(item, "id", ITEM_ID);
        return item;
    }

    private UsersOrders order(StoresItems item, long id) {
        UsersAccount consumer = new UsersAccount(null, UserType.CONSUMER, "consumer-" + id, "c" + id, "encoded");
        UsersOrders order = new UsersOrders(consumer, item, 1, "key-" + id);
        ReflectionTestUtils.setField(order, "id", id);
        return order;
    }
}
