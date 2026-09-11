package com.hwannee.ieum.stores.service;

import com.hwannee.ieum.auth.verify.principal.AuthenticatedUser;
import com.hwannee.ieum.stores.domain.Stores;
import com.hwannee.ieum.stores.exception.StoreException;
import com.hwannee.ieum.stores.repository.StoresRepository;
import com.hwannee.ieum.stores.web.dto.CreateStoreRequest;
import com.hwannee.ieum.stores.web.dto.StoreResponse;
import com.hwannee.ieum.users.domain.UsersAccount;
import com.hwannee.ieum.users.repository.UsersAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class StoreService {

    private final StoresRepository stores;
    private final UsersAccountRepository accounts;

    public StoreService(StoresRepository stores, UsersAccountRepository accounts) {
        this.stores = stores;
        this.accounts = accounts;
    }

    @Transactional
    public StoreResponse create(AuthenticatedUser owner, CreateStoreRequest request) {
        // TODO(정책): 자정을 넘기는 영업 시간(22:00 ~ 02:00) 허용 여부. 현재는 open < close 만 허용
        if (!request.openAt().isBefore(request.closeAt())) {
            throw new StoreException.InvalidBusinessHours();
        }
        // TODO(1.3 BUSINESS_OWNER 검증): BusinessRegistration 승인 여부를 여기서 볼지, 가입 시점에 볼지
        UsersAccount account = accounts.findByUid(owner.uid()).orElseThrow(StoreException.AccountNotFound::new);
        Stores store = new Stores(account, UUID.randomUUID().toString(), request.name(),
                request.storeType(), request.openAt(), request.closeAt());
        if (request.logoImgUrl() != null) {
            store.changeLogoImgUrl(request.logoImgUrl());
        }
        return StoreResponse.from(stores.save(store));
    }

    @Transactional(readOnly = true)
    public List<StoreResponse> findMine(AuthenticatedUser owner) {
        return stores.findAllByUsersAccount_UidOrderByIdDesc(owner.uid()).stream()
                .map(StoreResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public StoreResponse findByUid(String storeUid) {
        return StoreResponse.from(stores.findByUid(storeUid).orElseThrow(StoreException.StoreNotFound::new));
    }

    // TODO(README Merchant): 영업 종료(shutdown) — 활성 예약이 남아 있으면 어떻게 할지(거절 후 재고 복구 / 종료 거부) 결정
    // TODO(README Merchant): 예약·픽업 가능 시간 설정(openAt/closeAt 수정)

    // TODO(1.5 소유권 규약): OrderService.ownedByStoreOwner 와 같은 패턴. 규약 확정 후 공통 위치로
    Stores ownedBy(AuthenticatedUser owner, String storeUid) {
        Stores store = stores.findByUid(storeUid).orElseThrow(StoreException.StoreNotFound::new);
        if (!store.getUsersAccount().getUid().equals(owner.uid())) {
            throw new StoreException.NotStoreOwner();
        }
        return store;
    }
}
