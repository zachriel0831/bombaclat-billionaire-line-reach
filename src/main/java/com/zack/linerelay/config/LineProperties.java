package com.zack.linerelay.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

/**
 * Strongly typed view of the `line.*` configuration tree from application.yml
 * and `.env`.
 */
@Validated
@ConfigurationProperties(prefix = "line")
public record LineProperties(
        // Required because webhook verification and outbound push cannot be safely degraded.
        @NotBlank String channelSecret,
        @NotBlank String channelAccessToken,
        String apiBase,
        Platform platform,
        Push push,
        Mysql mysql
) {
    /**
     * Applies defensive defaults so local configuration can omit optional nested
     * sections while the rest of the application still sees non-null records.
     */
    @ConstructorBinding
    public LineProperties {
        if (apiBase == null || apiBase.isBlank()) {
            apiBase = "https://api.line.me";
        }
        if (platform == null) {
            platform = new Platform(true, null, null, null, null);
        }
        if (push == null) {
            push = new Push(false, true);
        }
        if (mysql == null) {
            mysql = new Mysql(false, null, null, null, null, null);
        }
    }

    public LineProperties(String channelSecret, String channelAccessToken, String apiBase, Push push, Mysql mysql) {
        this(channelSecret, channelAccessToken, apiBase, null, push, mysql);
    }

    /**
     * Runtime push defaults. The values seed {@link com.zack.linerelay.push.PushModeService};
     * webhook commands may change the in-memory state until the next restart.
     */
    public record Push(boolean enabled, boolean testOnly) {}

    /**
     * Middle-office API used for real-time stock model generation.
     */
    public record Platform(
            boolean enabled,
            String baseUrl,
            String stockSignalPath,
            String writeApiKeyHeader,
            String writeApiKey
    ) {
        public Platform(boolean enabled, String baseUrl, String stockSignalPath) {
            this(enabled, baseUrl, stockSignalPath, null, null);
        }

        public Platform {
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "http://localhost:8081";
            }
            if (stockSignalPath == null || stockSignalPath.isBlank()) {
                stockSignalPath = "/api/stock-signals/generate";
            }
            if (writeApiKeyHeader == null || writeApiKeyHeader.isBlank()) {
                writeApiKeyHeader = "X-News-Write-Key";
            }
            if (writeApiKey == null) {
                writeApiKey = "";
            }
        }
    }

    /**
     * Table names are configurable so the Java relay can point at an existing
     * collector database without hardcoding environment-specific names.
     */
    public record Mysql(
            boolean enabled,
            String analysisTable,
            String groupTable,
            String userTable,
            String tradeSignalTable,
            String macroCalendarTable
    ) {
        public Mysql(boolean enabled, String analysisTable, String groupTable, String userTable, String tradeSignalTable) {
            this(enabled, analysisTable, groupTable, userTable, tradeSignalTable, null);
        }

        /**
         * Keeps all repository SQL simple by normalizing blank table-name
         * configuration before beans are constructed.
         */
        public Mysql {
            if (analysisTable == null || analysisTable.isBlank()) {
                analysisTable = "t_market_analyses";
            }
            if (groupTable == null || groupTable.isBlank()) {
                groupTable = "t_bot_group_info";
            }
            if (userTable == null || userTable.isBlank()) {
                userTable = "t_bot_user_info";
            }
            if (tradeSignalTable == null || tradeSignalTable.isBlank()) {
                tradeSignalTable = "t_trade_signals";
            }
            if (macroCalendarTable == null || macroCalendarTable.isBlank()) {
                macroCalendarTable = "t_macro_release_calendar";
            }
        }
    }
}
