package com.hwannee.ieum.stores.web.dto;

import com.hwannee.ieum.stores.domain.StoreType;
import com.hwannee.ieum.stores.domain.Stores;

import java.time.LocalTime;

public record StoreResponse(
        String storeUid,
        String name,
        StoreType storeType,
        LocalTime openAt,
        LocalTime closeAt,
        String logoImgUrl,
        boolean shutdown
) {
    public static StoreResponse from(Stores store) {
        return new StoreResponse(
                store.getUid(),
                store.getName(),
                store.getStoreType(),
                store.getOpenAt(),
                store.getCloseAt(),
                store.getLogoImgUrl(),
                store.isShutdown()
        );
    }
}
