package com.hwannee.ieum.stores.service;

import com.hwannee.ieum.auth.verify.principal.AuthenticatedUser;
import com.hwannee.ieum.orders.domain.OrderState;
import com.hwannee.ieum.orders.repository.UsersOrdersRepository;
import com.hwannee.ieum.orders.service.ItemSaleCache;
import com.hwannee.ieum.stores.domain.Stores;
import com.hwannee.ieum.stores.exception.StoreException;
import com.hwannee.ieum.stores.repository.StoresItemsRepository;
import com.hwannee.ieum.stores.repository.StoresRepository;
import com.hwannee.ieum.stores.web.dto.CreateStoreRequest;
import com.hwannee.ieum.stores.web.dto.StoreResponse;
import com.hwannee.ieum.stores.web.dto.UpdateBusinessHoursRequest;
import com.hwannee.ieum.users.domain.UsersAccount;
import com.hwannee.ieum.users.repository.UsersAccountRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Service
public class StoreService {

    private final StoresRepository stores;
    private final UsersAccountRepository accounts;
    private final StoresItemsRepository items;
    private final UsersOrdersRepository orders;
    private final ObjectProvider<ItemSaleCache> saleCache;

    public StoreService(StoresRepository stores, UsersAccountRepository accounts, StoresItemsRepository items,
                        UsersOrdersRepository orders, ObjectProvider<ItemSaleCache> saleCache) {
        this.stores = stores;
        this.accounts = accounts;
        this.items = items;
        this.orders = orders;
        this.saleCache = saleCache;
    }

    @Transactional
    public StoreResponse create(AuthenticatedUser owner, CreateStoreRequest request) {
        // TODO(정책): 자정을 넘기는 영업 시간(22:00 ~ 02:00) 허용 여부. 현재는 open < close 만 허용
        requireValidHours(request.openAt(), request.closeAt());
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
    public List<StoreResponse> findOpen() {
        return stores.findTop50ByShutdownAtIsNullOrderByIdDesc().stream()
                .map(StoreResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public StoreResponse findByUid(String storeUid) {
        return StoreResponse.from(stores.findByUid(storeUid).orElseThrow(StoreException.StoreNotFound::new));
    }

    @Transactional
    public StoreResponse changeBusinessHours(AuthenticatedUser owner, String storeUid,
                                             UpdateBusinessHoursRequest request) {
        requireValidHours(request.openAt(), request.closeAt());
        Stores store = ownedBy(owner, storeUid);
        store.changeBusinessHours(request.openAt(), request.closeAt());
        return StoreResponse.from(store);
    }

    @Transactional
    public StoreResponse shutdown(AuthenticatedUser owner, String storeUid) {
        Stores store = ownedBy(owner, storeUid);
        if (store.isShutdown()) {
            throw new StoreException.StoreShutdown();
        }
        if (orders.existsByStoreAndStates(store.getId(), OrderState.ACTIVE)) {
            throw new StoreException.ActiveOrdersRemain();
        }
        store.shutdown();
        saleCache.ifAvailable(cache -> items.findAllByStore_UidOrderByIdDesc(storeUid)
                .forEach(item -> cache.evictAfterCommit(item.getUid())));
        return StoreResponse.from(store);
    }

    Stores ownedBy(AuthenticatedUser owner, String storeUid) {
        return stores.findByUidAndUsersAccount_Uid(storeUid, owner.uid())
                .orElseThrow(StoreException.StoreNotFound::new);
    }

    private static void requireValidHours(LocalTime openAt, LocalTime closeAt) {
        if (!openAt.isBefore(closeAt)) {
            throw new StoreException.InvalidBusinessHours();
        }
    }
}
