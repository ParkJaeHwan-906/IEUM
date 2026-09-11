package com.hwannee.ieum.stores.web.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public record CreateItemRequest(
        @NotBlank @Size(max = 50) String name,
        @Min(0) int originalPrice,
        @Min(0) int salePrice,
        // TODO(정책): 상품당 최대 등록 수량. 현재 임시로 10,000 (부하 테스트 시나리오가 10,000 요청)
        @Min(1) @Max(10_000) int initialQuantity,
        // 예약 마감 시각. 이후에는 예약 불가 (OrderService TODO 판매 조건)
        @NotNull @Future LocalDateTime lastOrderTime,
        @Size(max = 500) String itemImgUrl
) {
}
