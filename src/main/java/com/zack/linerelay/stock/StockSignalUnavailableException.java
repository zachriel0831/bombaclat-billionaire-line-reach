package com.zack.linerelay.stock;

public class StockSignalUnavailableException extends RuntimeException {

    public StockSignalUnavailableException(String message) {
        super(message);
    }

    public StockSignalUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
