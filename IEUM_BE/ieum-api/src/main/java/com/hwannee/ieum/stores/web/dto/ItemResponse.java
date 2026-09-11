package com.hwannee.ieum.stores.web.dto;

import com.hwannee.ieum.stores.domain.StoresItems;

import java.time.LocalDateTime;

// TODO(3단계 Redis): remainingQuantity 를 DB 가 아니라 Redis 재고 원장에서 읽을지 결정. 조회 API 는 permitAll 이라 트래픽이 많다
public record ItemResponse(
        String itemUid,
        String storeUid,
        String name,
        int originalPrice,
        int salePrice,
        int initialQuantity,
        int remainingQuantity,
        LocalDateTime lastOrderTime,
        String itemImgUrl
) {
    public static ItemResponse from(StoresItems item) {
        return new ItemResponse(
                item.getUid(),
                item.getStore().getUid(),
                item.getName(),
                item.getOriginalPrice(),
                item.getSalePrice(),
                item.getInitialQuantity(),
                item.getRemainingQuantity(),
                item.getLastOrderTime(),
                item.getItemImgUrl()
        );
    }
}
