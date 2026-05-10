package com.zack.linerelay.stock;

import java.time.Instant;

public record GeneratedStockSignalResponse(
        String ticker,
        String name,
        String market,
        String direction,
        String strategyType,
        String confidence,
        String entry,
        String takeProfit,
        String stopLoss,
        String risk,
        String modelAnalysis,
        String rationale,
        String disclaimer,
        String lineMessage,
        String source,
        String model,
        String promptVersion,
        String contextStatus,
        Instant generatedAt
) {
}
