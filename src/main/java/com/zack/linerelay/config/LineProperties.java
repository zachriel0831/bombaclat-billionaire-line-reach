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
        if (push == null) {
            push = new Push(false, true);
        }
        if (mysql == null) {
            mysql = new Mysql(false, null, null, null, null);
        }
    }

    /**
     * Runtime push defaults. The values seed {@link com.zack.linerelay.push.PushModeService};
     * webhook commands may change the in-memory state until the next restart.
     */
    public record Push(boolean enabled, boolean testOnly) {}

    /**
     * Table names are configurable so the Java relay can point at an existing
     * collector database without hardcoding environment-specific names.
     */
    public record Mysql(
            boolean enabled,
            String analysisTable,
            String groupTable,
            String userTable,
            String macroCalendarTable
    ) {
        public Mysql(boolean enabled, String analysisTable, String groupTable, String userTable) {
            this(enabled, analysisTable, groupTable, userTable, null);
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
            if (macroCalendarTable == null || macroCalendarTable.isBlank()) {
                macroCalendarTable = "t_macro_release_calendar";
            }
        }
    }
}
