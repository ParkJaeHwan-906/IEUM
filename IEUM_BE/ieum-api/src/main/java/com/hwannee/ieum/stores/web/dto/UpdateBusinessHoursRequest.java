package com.hwannee.ieum.stores.web.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;

public record UpdateBusinessHoursRequest(
        @NotNull LocalTime openAt,
        @NotNull LocalTime closeAt
) {
}
