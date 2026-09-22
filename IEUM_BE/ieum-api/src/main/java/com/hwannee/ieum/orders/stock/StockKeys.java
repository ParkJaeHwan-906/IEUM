package com.hwannee.ieum.orders.stock;

public final class StockKeys {

    public static final String STOCK_PREFIX = "stock:";
    public static final String EXPIRY_INDEX = "orders:expiry";

    private StockKeys() {
    }

    public static String stock(Long itemId) {
        return STOCK_PREFIX + itemId;
    }
}
