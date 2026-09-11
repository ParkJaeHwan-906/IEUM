package com.hwannee.ieum.stores.service;

import com.hwannee.ieum.auth.verify.principal.AuthenticatedUser;
import com.hwannee.ieum.stores.domain.Stores;
import com.hwannee.ieum.stores.domain.StoresItems;
import com.hwannee.ieum.stores.exception.StoreException;
import com.hwannee.ieum.stores.repository.StoresItemsRepository;
import com.hwannee.ieum.stores.repository.StoresRepository;
import com.hwannee.ieum.stores.web.dto.CreateItemRequest;
import com.hwannee.ieum.stores.web.dto.ItemResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class StoreItemService {

    private final StoresItemsRepository items;
    private final StoresRepository stores;
    private final StoreService storeService;

    public StoreItemService(StoresItemsRepository items, StoresRepository stores, StoreService storeService) {
        this.items = items;
        this.stores = stores;
        this.storeService = storeService;
    }

    @Transactional
    public ItemResponse create(AuthenticatedUser owner, String storeUid, CreateItemRequest request) {
        Stores store = storeService.ownedBy(owner, storeUid);
        if (store.isShutdown()) {
            throw new StoreException.StoreShutdown();
        }
        if (request.salePrice() > request.originalPrice()) {
            throw new StoreException.InvalidPrice();
        }
        StoresItems item = new StoresItems(store, UUID.randomUUID().toString(), request.itemImgUrl(),
                request.name(), request.originalPrice(), request.salePrice(),
                request.initialQuantity(), request.lastOrderTime());
        // TODO(3단계 Redis): 저장 직후 stock:{itemId} 를 initialQuantity 로 SET. DB 커밋 후에 실행되어야 함 (TransactionSynchronization)
        return ItemResponse.from(items.save(item));
    }

    @Transactional(readOnly = true)
    public ItemResponse findByUid(String itemUid) {
        return ItemResponse.from(items.findByUid(itemUid).orElseThrow(StoreException.ItemNotFound::new));
    }

    @Transactional(readOnly = true)
    public List<ItemResponse> findByStore(String storeUid) {
        if (stores.findByUid(storeUid).isEmpty()) {
            throw new StoreException.StoreNotFound();
        }
        return items.findAllByStore_UidOrderByIdDesc(storeUid).stream()
                .map(ItemResponse::from)
                .toList();
    }

    // TODO(README Merchant): 재고 조정 — initialQuantity 변경 시 remainingQuantity 를 같은 폭으로 조정. 불변식(initial = remaining + active + pickedUp) 유지
    // TODO(README Merchant): 상품 판매 종료 — lastOrderTime 을 now 로 당기는 방식인지 별도 플래그인지 결정
    // TODO(README Merchant): 상품별 예약 현황 조회 — UsersOrdersRepository 에 storeItem 기준 조회 추가
}
