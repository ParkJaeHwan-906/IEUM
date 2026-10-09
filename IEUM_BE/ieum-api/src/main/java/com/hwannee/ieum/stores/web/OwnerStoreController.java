package com.hwannee.ieum.stores.web;

import com.hwannee.ieum.auth.verify.principal.AuthenticatedUser;
import com.hwannee.ieum.auth.verify.principal.CurrentUser;
import com.hwannee.ieum.orders.domain.OrderState;
import com.hwannee.ieum.orders.web.dto.OrderResponse;
import com.hwannee.ieum.stores.service.StoreItemService;
import com.hwannee.ieum.stores.service.StoreService;
import com.hwannee.ieum.stores.web.dto.AdjustQuantityRequest;
import com.hwannee.ieum.stores.web.dto.CreateItemRequest;
import com.hwannee.ieum.stores.web.dto.CreateStoreRequest;
import com.hwannee.ieum.stores.web.dto.ItemResponse;
import com.hwannee.ieum.stores.web.dto.StoreResponse;
import com.hwannee.ieum.stores.web.dto.UpdateBusinessHoursRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    @PatchMapping("/{storeUid}")
    public StoreResponse changeBusinessHours(@CurrentUser AuthenticatedUser owner,
                                             @PathVariable String storeUid,
                                             @Valid @RequestBody UpdateBusinessHoursRequest request) {
        return storeService.changeBusinessHours(owner, storeUid, request);
    }

    @PostMapping("/{storeUid}/shutdown")
    public StoreResponse shutdown(@CurrentUser AuthenticatedUser owner, @PathVariable String storeUid) {
        return storeService.shutdown(owner, storeUid);
    }

    @PatchMapping("/{storeUid}/items/{itemUid}/quantity")
    public ItemResponse adjustQuantity(@CurrentUser AuthenticatedUser owner,
                                       @PathVariable String storeUid,
                                       @PathVariable String itemUid,
                                       @Valid @RequestBody AdjustQuantityRequest request) {
        return storeItemService.adjustQuantity(owner, storeUid, itemUid, request.initialQuantity());
    }

    @PostMapping("/{storeUid}/items/{itemUid}/close")
    public ItemResponse closeItem(@CurrentUser AuthenticatedUser owner,
                                  @PathVariable String storeUid,
                                  @PathVariable String itemUid) {
        return storeItemService.close(owner, storeUid, itemUid);
    }

    @GetMapping("/{storeUid}/items/{itemUid}/orders")
    public List<OrderResponse> itemOrders(@CurrentUser AuthenticatedUser owner,
                                          @PathVariable String storeUid,
                                          @PathVariable String itemUid,
                                          @RequestParam(required = false) OrderState state) {
        return storeItemService.findOrders(owner, storeUid, itemUid, state);
    }
}
