package com.zack.linerelay.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "line")
public record LineProperties(
        @NotBlank String channelSecret,
        @NotBlank String channelAccessToken,
        String apiBase,
        Push push,
        Mysql mysql
) {
    public LineProperties {
        if (apiBase == null || apiBase.isBlank()) {
            apiBase = "https://api.line.me";
        }
        if (push == null) {
            push = new Push(false);
        }
        if (mysql == null) {
            mysql = new Mysql(false, null, null, null);
        }
    }

    public record Push(boolean enabled) {}

    public record Mysql(
            boolean enabled,
            String analysisTable,
            String groupTable,
            String userTable
    ) {
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
        }
    }
}
