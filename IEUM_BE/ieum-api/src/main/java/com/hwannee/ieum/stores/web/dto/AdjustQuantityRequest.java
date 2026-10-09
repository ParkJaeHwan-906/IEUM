package com.hwannee.ieum.stores.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record AdjustQuantityRequest(
        @NotNull @Min(0) @Max(10_000) Integer initialQuantity
) {
}
