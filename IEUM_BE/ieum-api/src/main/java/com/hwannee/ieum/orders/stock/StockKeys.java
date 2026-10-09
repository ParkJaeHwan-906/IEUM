package com.hwannee.ieum.orders.stock;

public final class StockKeys {

    public static final String STOCK_PREFIX = "stock:";
    public static final String EXPIRY_INDEX = "orders:expiry";

    private StockKeys() {
    }

    public static String stock(Long itemId) {
        return STOCK_PREFIX + slot(itemId);
    }

    public static String active(Long itemId) {
        return stock(itemId) + ":active";
    }

    public static String idempotency(Long itemId, String userUid, String idempotencyKey) {
        return stock(itemId) + ":idem:" + userUid + ":" + idempotencyKey;
    }

    public static String restored(Long itemId, Long orderId) {
        return stock(itemId) + ":restored:" + orderId;
    }

    private static String slot(Long itemId) {
        return "{" + itemId + "}";
    }
}
