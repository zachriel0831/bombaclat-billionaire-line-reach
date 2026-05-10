package com.zack.linerelay.push;

import com.zack.linerelay.push.dto.MulticastRequest;
import com.zack.linerelay.push.dto.PushRequest;
import com.zack.linerelay.push.dto.PushRequest.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;

/**
 * Thin LINE Messaging API client for push and multicast calls.
 */
@Service
public class LinePushClient {

    private static final Logger log = LoggerFactory.getLogger(LinePushClient.class);

    private static final int MAX_TEXT_LENGTH = 5000;
    private static final int MULTICAST_BATCH_LIMIT = 500;

    private final RestClient restClient;
    private final PushModeService pushModeService;
    private final PushRateLimiter pushRateLimiter;

    public LinePushClient(
            @Qualifier("lineRestClient") RestClient lineRestClient,
            PushModeService pushModeService,
            PushRateLimiter pushRateLimiter
    ) {
        this.restClient = lineRestClient;
        this.pushModeService = pushModeService;
        this.pushRateLimiter = pushRateLimiter;
    }

    /**
     * Exposes the current runtime push toggle for orchestration and admin output.
     */
    public boolean isPushEnabled() {
        return pushModeService.isPushEnabled();
    }

    /**
     * Normal push path. It respects the master push toggle so scheduled/admin
     * runs can be dry-run safely.
     */
    public PushAttempt push(String targetId, String text) {
        return push(PushMessageType.PUBLIC_ANALYSIS, targetId, text);
    }

    public PushAttempt push(PushMessageType type, String targetId, String text) {
        if (targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException("targetId is required");
        }
        PushMessageType messageType = normalizeType(type);
        String payload = truncate(text);
        if (!isPushEnabled()) {
            log.info("LINE push DISABLED (line.push.enabled=false) type={} target={} text_chars={}",
                    messageType, targetId, payload.length());
            return PushAttempt.toggleSkipped();
        }
        return doPush(messageType, targetId, payload);
    }

    /**
     * Manual test command path. It bypasses only the master toggle; callers are
     * still responsible for restricting targets before calling this method.
     */
    public PushAttempt pushIgnoringToggle(String targetId, String text) {
        return pushIgnoringToggle(PushMessageType.PUBLIC_ANALYSIS, targetId, text);
    }

    public PushAttempt pushIgnoringToggle(PushMessageType type, String targetId, String text) {
        if (targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException("targetId is required");
        }
        return doPush(normalizeType(type), targetId, truncate(text));
    }

    /**
     * LINE multicast supports only user IDs and caps each request at 500 users.
     */
    public MulticastAttempt multicast(List<String> targetUserIds, String text) {
        return multicast(PushMessageType.PUBLIC_ANALYSIS, targetUserIds, text);
    }

    public MulticastAttempt multicast(PushMessageType type, List<String> targetUserIds, String text) {
        if (targetUserIds == null || targetUserIds.isEmpty()) {
            throw new IllegalArgumentException("targetUserIds is required");
        }
        PushMessageType messageType = normalizeType(type);
        String truncated = truncate(text);
        if (!isPushEnabled()) {
            log.info("LINE multicast DISABLED (line.push.enabled=false) type={} total_targets={} text_chars={}",
                    messageType, targetUserIds.size(), truncated.length());
            return new MulticastAttempt(0, targetUserIds.size(), 0);
        }
        List<String> allowedTargets = new ArrayList<>();
        List<PushRateLimiter.Lease> allowedLeases = new ArrayList<>();
        int skippedByRateLimit = 0;
        for (String targetId : targetUserIds) {
            PushRateLimiter.Lease lease = pushRateLimiter.acquire(messageType, targetId);
            if (!lease.allowed()) {
                skippedByRateLimit++;
                continue;
            }
            allowedTargets.add(targetId);
            allowedLeases.add(lease);
        }
        if (allowedTargets.isEmpty()) {
            log.warn("LINE multicast rate-limited all_targets type={} total_targets={} skipped_by_rate_limit={}",
                    messageType, targetUserIds.size(), skippedByRateLimit);
            return new MulticastAttempt(0, 0, skippedByRateLimit);
        }
        for (int start = 0; start < allowedTargets.size(); start += MULTICAST_BATCH_LIMIT) {
            int end = Math.min(start + MULTICAST_BATCH_LIMIT, allowedTargets.size());
            List<String> batch = allowedTargets.subList(start, end);
            List<PushRateLimiter.Lease> batchLeases = allowedLeases.subList(start, end);
            MulticastRequest body = new MulticastRequest(batch, List.of(Message.text(truncated)));
            try {
                restClient.post()
                        .uri("/v2/bot/message/multicast")
                        .body(body)
                        .retrieve()
                        .toBodilessEntity();
                log.info("LINE multicast ok type={} batch_size={}", messageType, batch.size());
            } catch (RestClientResponseException ex) {
                batchLeases.forEach(pushRateLimiter::rollback);
                log.error("LINE multicast failed type={} batch_size={} status={} body={}",
                        messageType, batch.size(), ex.getStatusCode().value(), ex.getResponseBodyAsString());
                throw ex;
            }
        }
        if (skippedByRateLimit > 0) {
            log.warn("LINE multicast rate-limited type={} skipped_targets={} delivered_targets={}",
                    messageType, skippedByRateLimit, allowedTargets.size());
        }
        return new MulticastAttempt(allowedTargets.size(), 0, skippedByRateLimit);
    }

    /**
     * Applies LINE's text limit before serializing the payload.
     */
    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > MAX_TEXT_LENGTH ? text.substring(0, MAX_TEXT_LENGTH) : text;
    }

    /**
     * Single-target LINE HTTP call shared by normal and manual push paths.
     */
    private PushAttempt doPush(PushMessageType type, String targetId, String payload) {
        PushRateLimiter.Lease lease = pushRateLimiter.acquire(type, targetId);
        if (!lease.allowed()) {
            log.warn("LINE push rate-limited type={} target={} business_date={} limit={}",
                    type, targetId, lease.businessDate(), lease.dailyLimit());
            return PushAttempt.rateLimitSkipped();
        }
        PushRequest body = new PushRequest(targetId, List.of(Message.text(payload)));
        try {
            restClient.post()
                    .uri("/v2/bot/message/push")
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            if (lease.businessDate() != null) {
                log.info("LINE push ok type={} target={} business_date={} used_count={} daily_limit={}",
                        type, targetId, lease.businessDate(), lease.usedCount(), lease.dailyLimit());
            } else {
                log.info("LINE push ok type={} target={}", type, targetId);
            }
            return PushAttempt.sent();
        } catch (RestClientResponseException ex) {
            pushRateLimiter.rollback(lease);
            log.error("LINE push failed type={} target={} status={} body={}",
                    type, targetId, ex.getStatusCode().value(), ex.getResponseBodyAsString());
            throw ex;
        }
    }

    private PushMessageType normalizeType(PushMessageType type) {
        return type == null ? PushMessageType.PUBLIC_ANALYSIS : type;
    }

    /**
     * Result shape for a single-target push attempt.
     */
    public record PushAttempt(boolean delivered, boolean skippedByToggle, boolean skippedByRateLimit) {
        public static PushAttempt sent() {
            return new PushAttempt(true, false, false);
        }

        public static PushAttempt toggleSkipped() {
            return new PushAttempt(false, true, false);
        }

        public static PushAttempt rateLimitSkipped() {
            return new PushAttempt(false, false, true);
        }
    }

    /**
     * Batch delivery summary for multicast callers.
     */
    public record MulticastAttempt(int delivered, int skippedByToggle, int skippedByRateLimit) {}
}
