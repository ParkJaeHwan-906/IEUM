package com.hwannee.ieum.stores.web;

import com.hwannee.ieum.stores.service.StoreItemService;
import com.hwannee.ieum.stores.service.StoreService;
import com.hwannee.ieum.stores.web.dto.ItemResponse;
import com.hwannee.ieum.stores.web.dto.StoreResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// GET /api/stores/** 는 SecurityConfig 에서 permitAll. 여기서는 @CurrentUser 를 쓰지 않는다 (쓰면 401)
@RestController
@RequestMapping("/api/stores")
public class StoreController {

    private final StoreService storeService;
    private final StoreItemService storeItemService;

    public StoreController(StoreService storeService, StoreItemService storeItemService) {
        this.storeService = storeService;
        this.storeItemService = storeItemService;
    }

    @GetMapping("/{storeUid}")
    public StoreResponse store(@PathVariable String storeUid) {
        return storeService.findByUid(storeUid);
    }

    @GetMapping("/{storeUid}/items")
    public List<ItemResponse> items(@PathVariable String storeUid) {
        return storeItemService.findByStore(storeUid);
    }

    // TODO(README Customer): GET /api/stores?region=... 지역별 판매 중 상품 조회. 위치 컬럼 설계 선행
}
