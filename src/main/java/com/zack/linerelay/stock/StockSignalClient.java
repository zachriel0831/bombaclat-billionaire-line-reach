package com.zack.linerelay.stock;

public interface StockSignalClient {

    GeneratedStockSignalResponse generate(String ticker);
}
