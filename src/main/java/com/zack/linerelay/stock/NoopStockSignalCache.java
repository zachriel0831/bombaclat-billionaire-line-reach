package com.zack.linerelay.stock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@ConditionalOnMissingBean(StockSignalCache.class)
class NoopStockSignalCache implements StockSignalCache {
    @Override
    public Optional<String> get(String ticker) {
        return Optional.empty();
    }

    @Override
    public void put(String ticker, String reply) {
        // no-op
    }
}
