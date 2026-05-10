package com.zack.linerelay.stock;

public record StockSignalGenerationRequest(
        String ticker,
        String source,
        String locale
) {
}
