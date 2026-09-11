package com.hwannee.ieum.orders.exception;

import com.hwannee.ieum.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public abstract class OrderException extends ApiException {

    protected OrderException(HttpStatus status, String message) {
        super(status, message);
    }

    public static final class AccountNotFound extends OrderException {
        public AccountNotFound() {
            super(HttpStatus.NOT_FOUND, "계정을 찾을 수 없습니다.");
        }
    }

    public static final class ItemNotFound extends OrderException {
        public ItemNotFound() {
            super(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다.");
        }
    }

    public static final class OrderNotFound extends OrderException {
        public OrderNotFound() {
            super(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다.");
        }
    }

    public static final class InsufficientStock extends OrderException {
        public InsufficientStock(int remaining) {
            super(HttpStatus.CONFLICT, "재고가 부족합니다. 남은 수량: " + remaining);
        }
    }

    // TODO(2.2 중복 예약): README 의 409 ACTIVE_RESERVATION_EXISTS
    public static final class DuplicateActiveOrder extends OrderException {
        public DuplicateActiveOrder() {
            super(HttpStatus.CONFLICT, "이미 진행 중인 예약이 있습니다.");
        }
    }

    // TODO(2.2 멱등성): README 의 409 IDEMPOTENCY_KEY_REUSED
    public static final class IdempotencyKeyReused extends OrderException {
        public IdempotencyKeyReused() {
            super(HttpStatus.CONFLICT, "같은 Idempotency-Key 로 다른 요청이 이미 처리되었습니다.");
        }
    }

    public static final class NotStoreOwner extends OrderException {
        public NotStoreOwner() {
            super(HttpStatus.FORBIDDEN, "해당 가게의 점주만 처리할 수 있습니다.");
        }
    }

    public static final class ItemNotOnSale extends OrderException {
        public ItemNotOnSale() {
            super(HttpStatus.CONFLICT, "예약할 수 없는 상품입니다.");
        }
    }
}
