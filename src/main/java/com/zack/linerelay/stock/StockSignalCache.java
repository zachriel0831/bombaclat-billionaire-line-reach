package com.zack.linerelay.stock;

import java.util.Optional;

public interface StockSignalCache {
    Optional<String> get(String ticker);

    void put(String ticker, String reply);
}
