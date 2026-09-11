package com.hwannee.ieum.orders.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

// userId 는 본문에 두지 않는다. 토큰에서만 추출한다 (ADR-0001, TODO 1.5)
public record CreateOrderRequest(
        @NotBlank String itemUid,
        // TODO(정책): 1인 최대 예약 수량. 현재 임시로 10
        @Min(1) @Max(10) int quantity
) {
}
