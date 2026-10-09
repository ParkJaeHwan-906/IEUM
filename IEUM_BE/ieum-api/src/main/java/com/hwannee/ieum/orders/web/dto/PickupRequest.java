package com.hwannee.ieum.orders.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record PickupRequest(
        @NotBlank @Pattern(regexp = "[0-9]{6}") String pickupCode
) {
}
