package com.hwannee.ieum.stores.service;

import com.hwannee.ieum.auth.verify.principal.AuthenticatedUser;
import com.hwannee.ieum.orders.domain.OrderState;
import com.hwannee.ieum.orders.repository.UsersOrdersRepository;
import com.hwannee.ieum.orders.service.ItemSaleCache;
import com.hwannee.ieum.stores.domain.StoreType;
import com.hwannee.ieum.stores.domain.Stores;
import com.hwannee.ieum.stores.exception.StoreException;
import com.hwannee.ieum.stores.repository.StoresItemsRepository;
import com.hwannee.ieum.stores.repository.StoresRepository;
import com.hwannee.ieum.stores.web.dto.StoreResponse;
import com.hwannee.ieum.stores.web.dto.UpdateBusinessHoursRequest;
import com.hwannee.ieum.users.domain.UserType;
import com.hwannee.ieum.users.domain.UsersAccount;
import com.hwannee.ieum.users.repository.UsersAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class StoreServiceTest {

    private static final String OWNER_UID = "owner-uid";
    private static final String STORE_UID = "store-uid";
    private static final long STORE_ID = 5L;

    @Mock
    StoresRepository stores;

    @Mock
    UsersAccountRepository accounts;

    @Mock
    StoresItemsRepository items;

    @Mock
    UsersOrdersRepository orders;

    @Mock
    ObjectProvider<ItemSaleCache> saleCache;

    @InjectMocks
    StoreService service;

    AuthenticatedUser owner;
    Stores store;

    @BeforeEach
    void setUp() {
        owner = new AuthenticatedUser(OWNER_UID, UserType.BUSINESS_OWNER, "owner");
        UsersAccount account = new UsersAccount(null, UserType.BUSINESS_OWNER, OWNER_UID, "owner", "encoded");
        store = new Stores(account, STORE_UID, "가게", StoreType.CAFE, LocalTime.of(9, 0), LocalTime.of(18, 0));
        ReflectionTestUtils.setField(store, "id", STORE_ID);
    }

    @Test
    void 소유권은_점주_uid_를_조건에_넣은_조회로_확인한다() {
        givenOwned();

        assertThat(service.ownedBy(owner, STORE_UID)).isSameAs(store);
        then(stores).should(never()).findByUid(any());
    }

    @Test
    void 다른_점주의_가게는_없는_것처럼_StoreNotFound() {
        AuthenticatedUser stranger = new AuthenticatedUser("other-owner", UserType.BUSINESS_OWNER, "other");
        given(stores.findByUidAndUsersAccount_Uid(STORE_UID, "other-owner")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.ownedBy(stranger, STORE_UID))
                .isInstanceOf(StoreException.StoreNotFound.class);
        assertThatThrownBy(() -> service.shutdown(stranger, STORE_UID))
                .isInstanceOf(StoreException.StoreNotFound.class);
        assertThatThrownBy(() -> service.changeBusinessHours(stranger, STORE_UID, hours(10, 20)))
                .isInstanceOf(StoreException.StoreNotFound.class);
    }

    @Test
    void 영업_중인_가게만_최근_순으로_돌려준다() {
        given(stores.findTop50ByShutdownAtIsNullOrderByIdDesc()).willReturn(List.of(store));

        List<StoreResponse> open = service.findOpen();

        assertThat(open).extracting(StoreResponse::storeUid).containsExactly(STORE_UID);
    }

    @Test
    void 영업_시간을_바꾼다() {
        givenOwned();

        StoreResponse response = service.changeBusinessHours(owner, STORE_UID, hours(10, 21));

        assertThat(response.openAt()).isEqualTo(LocalTime.of(10, 0));
        assertThat(response.closeAt()).isEqualTo(LocalTime.of(21, 0));
    }

    @Test
    void 시작이_종료보다_늦으면_InvalidBusinessHours_이고_바꾸지_않는다() {
        assertThatThrownBy(() -> service.changeBusinessHours(owner, STORE_UID, hours(21, 9)))
                .isInstanceOf(StoreException.InvalidBusinessHours.class);
        assertThat(store.getOpenAt()).isEqualTo(LocalTime.of(9, 0));
    }

    @Test
    void 진행_중인_예약이_있으면_영업을_종료할_수_없다() {
        givenOwned();
        given(orders.existsByStoreAndStates(STORE_ID, OrderState.ACTIVE)).willReturn(true);

        assertThatThrownBy(() -> service.shutdown(owner, STORE_UID))
                .isInstanceOf(StoreException.ActiveOrdersRemain.class)
                .hasMessage("진행 중인 예약이 있어 영업을 종료할 수 없습니다.");
        assertThat(store.isShutdown()).isFalse();
    }

    @Test
    void 이미_종료된_가게는_StoreShutdown() {
        store.shutdown();
        givenOwned();

        assertThatThrownBy(() -> service.shutdown(owner, STORE_UID))
                .isInstanceOf(StoreException.StoreShutdown.class);
        then(orders).should(never()).existsByStoreAndStates(anyLong(), any());
    }

    @Test
    void 진행_중인_예약이_없으면_영업을_종료하고_판매_캐시를_비운다() {
        givenOwned();
        given(orders.existsByStoreAndStates(STORE_ID, OrderState.ACTIVE)).willReturn(false);

        StoreResponse response = service.shutdown(owner, STORE_UID);

        assertThat(response.shutdown()).isTrue();
        assertThat(store.isShutdown()).isTrue();
        then(saleCache).should().ifAvailable(any());
    }

    private void givenOwned() {
        given(stores.findByUidAndUsersAccount_Uid(STORE_UID, OWNER_UID)).willReturn(Optional.of(store));
    }

    private static UpdateBusinessHoursRequest hours(int open, int close) {
        return new UpdateBusinessHoursRequest(LocalTime.of(open, 0), LocalTime.of(close, 0));
    }
}
