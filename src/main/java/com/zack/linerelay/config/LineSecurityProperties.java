package com.zack.linerelay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@ConfigurationProperties(prefix = "line.security")
public class LineSecurityProperties {

    private boolean enabled = true;
    private String adminApiKeyHeader = "X-Line-Admin-Key";
    private String adminApiKeys = "";
    private int adminRateLimitPerMinute = 30;
    private int webhookRateLimitPerMinute = 120;

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String adminApiKeyHeader() {
        return adminApiKeyHeader;
    }

    public void setAdminApiKeyHeader(String adminApiKeyHeader) {
        this.adminApiKeyHeader = adminApiKeyHeader;
    }

    public String adminApiKeys() {
        return adminApiKeys;
    }

    public void setAdminApiKeys(String adminApiKeys) {
        this.adminApiKeys = adminApiKeys;
    }

    public int adminRateLimitPerMinute() {
        return adminRateLimitPerMinute;
    }

    public void setAdminRateLimitPerMinute(int adminRateLimitPerMinute) {
        this.adminRateLimitPerMinute = adminRateLimitPerMinute;
    }

    public int webhookRateLimitPerMinute() {
        return webhookRateLimitPerMinute;
    }

    public void setWebhookRateLimitPerMinute(int webhookRateLimitPerMinute) {
        this.webhookRateLimitPerMinute = webhookRateLimitPerMinute;
    }

    public boolean hasAdminKeys() {
        return !configuredAdminKeys().isEmpty();
    }

    public boolean validAdminKey(String key) {
        return key != null && configuredAdminKeys().contains(key);
    }

    private Set<String> configuredAdminKeys() {
        if (adminApiKeys == null || adminApiKeys.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(adminApiKeys.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }
}

