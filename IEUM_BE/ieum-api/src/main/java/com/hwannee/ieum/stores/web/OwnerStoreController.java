package com.hwannee.ieum.stores.web;

import com.hwannee.ieum.auth.verify.principal.AuthenticatedUser;
import com.hwannee.ieum.auth.verify.principal.CurrentUser;
import com.hwannee.ieum.stores.service.StoreItemService;
import com.hwannee.ieum.stores.service.StoreService;
import com.hwannee.ieum.stores.web.dto.CreateItemRequest;
import com.hwannee.ieum.stores.web.dto.CreateStoreRequest;
import com.hwannee.ieum.stores.web.dto.ItemResponse;
import com.hwannee.ieum.stores.web.dto.StoreResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/owner/stores")
@PreAuthorize("hasRole('BUSINESS_OWNER')")
public class OwnerStoreController {

    private final StoreService storeService;
    private final StoreItemService storeItemService;

    public OwnerStoreController(StoreService storeService, StoreItemService storeItemService) {
        this.storeService = storeService;
        this.storeItemService = storeItemService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StoreResponse create(@CurrentUser AuthenticatedUser owner, @Valid @RequestBody CreateStoreRequest request) {
        return storeService.create(owner, request);
    }

    @GetMapping("/me")
    public List<StoreResponse> mine(@CurrentUser AuthenticatedUser owner) {
        return storeService.findMine(owner);
    }

    @PostMapping("/{storeUid}/items")
    @ResponseStatus(HttpStatus.CREATED)
    public ItemResponse createItem(@CurrentUser AuthenticatedUser owner,
                                   @PathVariable String storeUid,
                                   @Valid @RequestBody CreateItemRequest request) {
        return storeItemService.create(owner, storeUid, request);
    }

    // TODO(README Merchant): PATCH /{storeUid} 영업 시간 수정, POST /{storeUid}/shutdown
    // TODO(README Merchant): PATCH /{storeUid}/items/{itemUid}/quantity 재고 조정, POST .../close 판매 종료
    // TODO(README Merchant): GET /{storeUid}/items/{itemUid}/orders 상품별 예약 현황
}
