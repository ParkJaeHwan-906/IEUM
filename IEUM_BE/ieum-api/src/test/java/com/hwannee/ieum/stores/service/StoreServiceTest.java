package com.hwannee.ieum.stores.service;

import com.hwannee.ieum.auth.verify.principal.AuthenticatedUser;
import com.hwannee.ieum.stores.domain.StoreType;
import com.hwannee.ieum.stores.domain.Stores;
import com.hwannee.ieum.stores.exception.StoreException;
import com.hwannee.ieum.stores.repository.StoresRepository;
import com.hwannee.ieum.users.domain.UserType;
import com.hwannee.ieum.users.domain.UsersAccount;
import com.hwannee.ieum.users.repository.UsersAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class StoreServiceTest {

    private static final String OWNER_UID = "owner-uid";
    private static final String STORE_UID = "store-uid";

    @Mock
    StoresRepository stores;

    @Mock
    UsersAccountRepository accounts;

    @InjectMocks
    StoreService service;

    AuthenticatedUser owner;
    Stores store;

    @BeforeEach
    void setUp() {
        owner = new AuthenticatedUser(OWNER_UID, UserType.BUSINESS_OWNER, "owner");
        UsersAccount account = new UsersAccount(null, UserType.BUSINESS_OWNER, OWNER_UID, "owner", "encoded");
        store = new Stores(account, STORE_UID, "가게", StoreType.CAFE, LocalTime.of(9, 0), LocalTime.of(18, 0));
    }

    @Test
    void 소유권은_점주_uid_를_조건에_넣은_조회로_확인한다() {
        given(stores.findByUidAndUsersAccount_Uid(STORE_UID, OWNER_UID)).willReturn(Optional.of(store));

        assertThat(service.ownedBy(owner, STORE_UID)).isSameAs(store);
        then(stores).should(never()).findByUid(any());
    }

    @Test
    void 다른_점주의_가게는_없는_것처럼_StoreNotFound() {
        AuthenticatedUser stranger = new AuthenticatedUser("other-owner", UserType.BUSINESS_OWNER, "other");
        given(stores.findByUidAndUsersAccount_Uid(STORE_UID, "other-owner")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.ownedBy(stranger, STORE_UID))
                .isInstanceOf(StoreException.StoreNotFound.class);
    }
}
