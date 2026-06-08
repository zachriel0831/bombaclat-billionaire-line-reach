package com.zack.linerelay.config;

import com.zack.linerelay.push.PushMessageType;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redis-backed push frequency cap configuration.
 */
@ConfigurationProperties(prefix = "line.push.rate-limit")
public record PushRateLimitProperties(
        boolean enabled,
        int dailyMaxPerTarget,
        int publicAnalysisDailyMaxPerTarget,
        int stockQueryDailyMaxPerTarget,
        int macroCalendarDailyMaxPerTarget,
        String zone,
        String keyPrefix
) {
    public PushRateLimitProperties {
        if (dailyMaxPerTarget <= 0) {
            dailyMaxPerTarget = 2;
        }
        if (publicAnalysisDailyMaxPerTarget <= 0) {
            publicAnalysisDailyMaxPerTarget = dailyMaxPerTarget;
        }
        if (stockQueryDailyMaxPerTarget <= 0) {
            stockQueryDailyMaxPerTarget = 3;
        }
        if (macroCalendarDailyMaxPerTarget <= 0) {
            macroCalendarDailyMaxPerTarget = 3;
        }
        if (zone == null || zone.isBlank()) {
            zone = "Asia/Taipei";
        }
        if (keyPrefix == null || keyPrefix.isBlank()) {
            keyPrefix = "line:push:rate-limit";
        }
    }

    public int dailyLimitFor(PushMessageType type) {
        return switch (type) {
            case STOCK_QUERY -> stockQueryDailyMaxPerTarget;
            case MACRO_CALENDAR -> macroCalendarDailyMaxPerTarget;
            case PUBLIC_ANALYSIS -> publicAnalysisDailyMaxPerTarget;
        };
    }
}
