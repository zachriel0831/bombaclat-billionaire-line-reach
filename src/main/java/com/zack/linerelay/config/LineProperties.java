package com.zack.linerelay.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "line")
public record LineProperties(
        @NotBlank String channelSecret,
        @NotBlank String channelAccessToken,
        String apiBase
) {
    public LineProperties {
        if (apiBase == null || apiBase.isBlank()) {
            apiBase = "https://api.line.me";
        }
    }
}
