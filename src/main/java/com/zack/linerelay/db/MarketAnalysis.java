package com.zack.linerelay.db;

import java.time.Instant;

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
