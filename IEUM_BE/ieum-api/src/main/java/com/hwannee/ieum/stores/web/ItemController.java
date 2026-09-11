package com.hwannee.ieum.stores.web;

import com.hwannee.ieum.stores.service.StoreItemService;
import com.hwannee.ieum.stores.web.dto.ItemResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// GET /api/items/** 는 SecurityConfig 에서 permitAll
@RestController
@RequestMapping("/api/items")
public class ItemController {

    private final StoreItemService storeItemService;

    public ItemController(StoreItemService storeItemService) {
        this.storeItemService = storeItemService;
    }

    // 상품 상세 및 남은 수량 확인 (README Customer). k6 가 초과 예약 여부를 확인할 때도 이 응답의 remainingQuantity 를 본다
    @GetMapping("/{itemUid}")
    public ItemResponse item(@PathVariable String itemUid) {
        return storeItemService.findByUid(itemUid);
    }
}
