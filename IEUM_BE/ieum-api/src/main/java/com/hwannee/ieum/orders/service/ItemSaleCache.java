package com.hwannee.ieum.orders.service;

import com.hwannee.ieum.orders.config.OrderProperties;
import com.hwannee.ieum.orders.exception.OrderException;
import com.hwannee.ieum.stores.domain.StoresItems;
import com.hwannee.ieum.stores.repository.StoresItemsRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@ConditionalOnProperty(name = "ieum.stock.strategy", havingValue = "redis")
public class ItemSaleCache {

    public record ItemSale(Long itemId, LocalDateTime lastOrderTime, boolean storeShutdown) {

        public boolean onSaleAt(LocalDateTime now) {
            return !storeShutdown && !now.isAfter(lastOrderTime);
        }
    }

    private record Entry(ItemSale sale, Instant loadedAt) {
    }

    private final StoresItemsRepository items;
    private final TransactionTemplate readOnly;
    private final Duration ttl;
    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();

    public ItemSaleCache(StoresItemsRepository items, PlatformTransactionManager transactionManager,
                         OrderProperties properties) {
        this.items = items;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.ttl = properties.itemCacheTtl();
    }

    public ItemSale get(String itemUid) {
        Instant now = Instant.now();
        return entries.compute(itemUid, (uid, cached) ->
                cached != null && cached.loadedAt().plus(ttl).isAfter(now) ? cached : new Entry(load(uid), now)
        ).sale();
    }

    public void evictAfterCommit(String itemUid) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            entries.remove(itemUid);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                entries.remove(itemUid);
            }
        });
    }

    private ItemSale load(String itemUid) {
        return readOnly.execute(status -> {
            StoresItems item = items.findByUid(itemUid).orElseThrow(OrderException.ItemNotFound::new);
            return new ItemSale(item.getId(), item.getLastOrderTime(), item.getStore().isShutdown());
        });
    }
}
