package com.hwannee.ieum.orders.integration;

import com.hwannee.ieum.stores.domain.StoresItems;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "ieum.stock.strategy=optimistic")
class OptimisticOrderConcurrencyIT extends OrderConcurrencyScenario {

    @Override
    boolean closesDuplicateGap() {
        return false;
    }

    @Override
    long ledgerRemaining(StoresItems item) {
        return items.findById(item.getId()).orElseThrow().getRemainingQuantity();
    }
}
