package com.zack.linerelay.db;

import java.time.Instant;

/**
 * Immutable projection of one `t_market_analyses` row used by push rendering.
 */
public record MarketAnalysis(
        long id,
        String analysisDate,
        String analysisSlot,
        String scheduledTimeLocal,
        String model,
        String promptVersion,
        String summaryText,
        String rawJson,
        Instant updatedAt
) {}
