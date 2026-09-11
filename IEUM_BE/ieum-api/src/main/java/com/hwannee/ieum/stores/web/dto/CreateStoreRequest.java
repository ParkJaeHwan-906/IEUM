package com.hwannee.ieum.stores.web.dto;

import com.hwannee.ieum.stores.domain.StoreType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;

public record CreateStoreRequest(
        @NotBlank @Size(max = 20) String name,
        @NotNull StoreType storeType,
        @NotNull LocalTime openAt,
        @NotNull LocalTime closeAt,
        // TODO(이미지): 업로드 방식(프리사인 URL 등) 결정 전까지는 URL 문자열만 받는다
        @Size(max = 255) String logoImgUrl
) {
}
