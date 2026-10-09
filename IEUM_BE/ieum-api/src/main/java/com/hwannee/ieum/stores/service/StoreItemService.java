package com.hwannee.ieum.stores.service;

import com.hwannee.ieum.auth.verify.principal.AuthenticatedUser;
import com.hwannee.ieum.orders.domain.OrderState;
import com.hwannee.ieum.orders.domain.UsersOrders;
import com.hwannee.ieum.orders.repository.UsersOrdersRepository;
import com.hwannee.ieum.orders.service.ItemSaleCache;
import com.hwannee.ieum.orders.stock.StockDeductionStrategy;
import com.hwannee.ieum.orders.web.dto.OrderResponse;
import com.hwannee.ieum.stores.domain.Stores;
import com.hwannee.ieum.stores.domain.StoresItems;
import com.hwannee.ieum.stores.exception.StoreException;
import com.hwannee.ieum.stores.repository.StoresItemsRepository;
import com.hwannee.ieum.stores.repository.StoresRepository;
import com.hwannee.ieum.stores.web.dto.CreateItemRequest;
import com.hwannee.ieum.stores.web.dto.ItemResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class StoreItemService {

    private final StoresItemsRepository items;
    private final StoresRepository stores;
    private final StoreService storeService;
    private final StockDeductionStrategy stock;
    private final UsersOrdersRepository orders;
    private final ObjectProvider<ItemSaleCache> saleCache;

    public StoreItemService(StoresItemsRepository items, StoresRepository stores, StoreService storeService,
                            StockDeductionStrategy stock, UsersOrdersRepository orders,
                            ObjectProvider<ItemSaleCache> saleCache) {
        this.items = items;
        this.stores = stores;
        this.storeService = storeService;
        this.stock = stock;
        this.orders = orders;
        this.saleCache = saleCache;
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
        StoresItems saved = items.save(item);
        stock.initialize(saved.getId(), saved.getInitialQuantity());
        return ItemResponse.from(saved);
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

    @Transactional
    public ItemResponse adjustQuantity(AuthenticatedUser owner, String storeUid, String itemUid, int initialQuantity) {
        StoresItems item = ownedItemForUpdate(owner, storeUid, itemUid);
        if (!stock.adjust(item, initialQuantity)) {
            throw new StoreException.QuantityBelowHeld();
        }
        return ItemResponse.from(item);
    }

    @Transactional
    public ItemResponse close(AuthenticatedUser owner, String storeUid, String itemUid) {
        StoresItems item = ownedItemForUpdate(owner, storeUid, itemUid);
        LocalDateTime now = LocalDateTime.now();
        if (item.isSaleClosedAt(now)) {
            throw new StoreException.ItemSaleClosed();
        }
        item.closeSale(now);
        saleCache.ifAvailable(cache -> cache.evictAfterCommit(item.getUid()));
        return ItemResponse.from(item);
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> findOrders(AuthenticatedUser owner, String storeUid, String itemUid, OrderState state) {
        StoresItems item = ownedItem(owner, storeUid, itemUid);
        List<UsersOrders> found = state == null
                ? orders.findAllByStoresItem_IdOrderByIdDesc(item.getId())
                : orders.findAllByStoresItem_IdAndOrderStateOrderByIdDesc(item.getId(), state);
        return found.stream()
                .map(OrderResponse::forOwner)
                .toList();
    }

    private StoresItems ownedItem(AuthenticatedUser owner, String storeUid, String itemUid) {
        return items.findOwnedByUid(itemUid, storeUid, owner.uid())
                .orElseThrow(StoreException.ItemNotFound::new);
    }

    private StoresItems ownedItemForUpdate(AuthenticatedUser owner, String storeUid, String itemUid) {
        return items.findOwnedByUidForUpdate(itemUid, storeUid, owner.uid())
                .orElseThrow(StoreException.ItemNotFound::new);
    }
}
