package com.zack.linerelay.push;

import com.zack.linerelay.config.LineProperties;
import com.zack.linerelay.push.dto.MulticastRequest;
import com.zack.linerelay.push.dto.PushRequest;
import com.zack.linerelay.push.dto.PushRequest.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

@Service
public class LinePushClient {

    private static final Logger log = LoggerFactory.getLogger(LinePushClient.class);

    private static final int MAX_TEXT_LENGTH = 5000;
    private static final int MULTICAST_BATCH_LIMIT = 500;

    private final RestClient restClient;
    private final LineProperties lineProperties;

    public LinePushClient(RestClient lineRestClient, LineProperties lineProperties) {
        this.restClient = lineRestClient;
        this.lineProperties = lineProperties;
    }

    public boolean isPushEnabled() {
        return lineProperties.push() != null && lineProperties.push().enabled();
    }

    public void push(String targetId, String text) {
        if (targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException("targetId is required");
        }
        String payload = truncate(text);
        if (!isPushEnabled()) {
            log.info("LINE push DISABLED (line.push.enabled=false) target={} text_chars={}",
                    targetId, payload.length());
            return;
        }
        PushRequest body = new PushRequest(targetId, List.of(Message.text(payload)));
        try {
            restClient.post()
                    .uri("/v2/bot/message/push")
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("LINE push ok target={}", targetId);
        } catch (RestClientResponseException ex) {
            log.error("LINE push failed target={} status={} body={}",
                    targetId, ex.getStatusCode().value(), ex.getResponseBodyAsString());
            throw ex;
        }
    }

    public void multicast(List<String> targetUserIds, String text) {
        if (targetUserIds == null || targetUserIds.isEmpty()) {
            throw new IllegalArgumentException("targetUserIds is required");
        }
        String truncated = truncate(text);
        if (!isPushEnabled()) {
            log.info("LINE multicast DISABLED (line.push.enabled=false) total_targets={} text_chars={}",
                    targetUserIds.size(), truncated.length());
            return;
        }
        for (int start = 0; start < targetUserIds.size(); start += MULTICAST_BATCH_LIMIT) {
            int end = Math.min(start + MULTICAST_BATCH_LIMIT, targetUserIds.size());
            List<String> batch = targetUserIds.subList(start, end);
            MulticastRequest body = new MulticastRequest(batch, List.of(Message.text(truncated)));
            try {
                restClient.post()
                        .uri("/v2/bot/message/multicast")
                        .body(body)
                        .retrieve()
                        .toBodilessEntity();
                log.info("LINE multicast ok batch_size={}", batch.size());
            } catch (RestClientResponseException ex) {
                log.error("LINE multicast failed batch_size={} status={} body={}",
                        batch.size(), ex.getStatusCode().value(), ex.getResponseBodyAsString());
                throw ex;
            }
        }
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > MAX_TEXT_LENGTH ? text.substring(0, MAX_TEXT_LENGTH) : text;
    }
}
