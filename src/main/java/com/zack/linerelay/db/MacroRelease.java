package com.zack.linerelay.db;

import java.time.LocalDateTime;

/**
 * Official macro release-calendar row prepared by data-collecting.
 */
public record MacroRelease(
        long id,
        String indicatorCode,
        String indicatorName,
        String periodLabel,
        String releaseTitle,
        LocalDateTime releaseAtTaipei,
        String sourceName,
        String sourceUrl,
        int importance
) {
}
