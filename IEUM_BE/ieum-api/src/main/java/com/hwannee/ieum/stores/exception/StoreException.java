package com.hwannee.ieum.stores.exception;

import com.hwannee.ieum.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public abstract class StoreException extends ApiException {

    protected StoreException(HttpStatus status, String message) {
        super(status, message);
    }

    public static final class AccountNotFound extends StoreException {
        public AccountNotFound() {
            super(HttpStatus.NOT_FOUND, "계정을 찾을 수 없습니다.");
        }
    }

    public static final class StoreNotFound extends StoreException {
        public StoreNotFound() {
            super(HttpStatus.NOT_FOUND, "가게를 찾을 수 없습니다.");
        }
    }

    public static final class ItemNotFound extends StoreException {
        public ItemNotFound() {
            super(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다.");
        }
    }

    public static final class NotStoreOwner extends StoreException {
        public NotStoreOwner() {
            super(HttpStatus.FORBIDDEN, "해당 가게의 점주만 처리할 수 있습니다.");
        }
    }

    public static final class StoreShutdown extends StoreException {
        public StoreShutdown() {
            super(HttpStatus.CONFLICT, "영업 종료된 가게입니다.");
        }
    }

    public static final class InvalidPrice extends StoreException {
        public InvalidPrice() {
            super(HttpStatus.BAD_REQUEST, "할인가는 정가를 넘을 수 없습니다.");
        }
    }

    public static final class InvalidBusinessHours extends StoreException {
        public InvalidBusinessHours() {
            super(HttpStatus.BAD_REQUEST, "영업 시작 시각이 종료 시각보다 늦을 수 없습니다.");
        }
    }
}
