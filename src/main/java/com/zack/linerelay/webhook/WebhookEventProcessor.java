package com.zack.linerelay.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.zack.linerelay.db.BotTargetRepository;
import com.zack.linerelay.market.MarketAnalysisPoller;
import com.zack.linerelay.push.PushModeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Converts verified LINE webhook events into database target state changes and
 * runtime push commands.
 */
@Component
public class WebhookEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(WebhookEventProcessor.class);
    private static final String TEST_MODE_PREFIX = "測試西卡卡";
    private static final String DISABLE_PUSH_PREFIX = "關閉西卡卡";
    private static final String PUSH_LATEST_TEST_PREFIX = "西卡卡推送";

    private final BotTargetRepository repository;
    private final MarketAnalysisPoller poller;
    private final PushModeService pushModeService;

    public WebhookEventProcessor(
            ObjectProvider<BotTargetRepository> repositoryProvider,
            ObjectProvider<MarketAnalysisPoller> pollerProvider,
            PushModeService pushModeService
    ) {
        this.repository = repositoryProvider.getIfAvailable();
        this.poller = pollerProvider.getIfAvailable();
        this.pushModeService = pushModeService;
    }

    /**
     * Processes one LINE webhook batch. State changes are collected first so
     * duplicate events in the same batch collapse to the final intended active
     * state before writing to the database.
     */
    public Summary process(JsonNode events) {
        Map<String, Boolean> userStates = new LinkedHashMap<>();
        Map<String, Boolean> groupStates = new LinkedHashMap<>();
        int commands = 0;

        if (events != null && events.isArray()) {
            for (JsonNode event : events) {
                collectState(event, userStates, groupStates);
                if (handleCommand(event)) {
                    commands++;
                }
            }
        }

        int activeUsers = 0;
        int inactiveUsers = 0;
        int activeGroups = 0;
        int inactiveGroups = 0;

        if (repository == null) {
            if (!userStates.isEmpty() || !groupStates.isEmpty()) {
                log.warn("LINE webhook received but MySQL store is disabled (users={} groups={})",
                        userStates.size(), groupStates.size());
            }
        } else {
            for (Map.Entry<String, Boolean> e : userStates.entrySet()) {
                repository.upsertUser(e.getKey(), e.getValue());
                if (e.getValue()) activeUsers++; else inactiveUsers++;
            }
            for (Map.Entry<String, Boolean> e : groupStates.entrySet()) {
                repository.upsertGroup(e.getKey(), e.getValue());
                if (e.getValue()) activeGroups++; else inactiveGroups++;
            }
        }

        int eventCount = events != null && events.isArray() ? events.size() : 0;
        log.info("webhook_processed events={} users={} groups={} active_users={} inactive_users={} active_groups={} inactive_groups={} commands={}",
                eventCount, userStates.size(), groupStates.size(),
                activeUsers, inactiveUsers, activeGroups, inactiveGroups, commands);

        return new Summary(eventCount, userStates.size(), groupStates.size(),
                activeUsers, inactiveUsers, activeGroups, inactiveGroups, commands);
    }

    /**
     * Extracts user/group active-state hints from a single LINE event. The maps
     * act as a small unit-of-work before repository writes happen.
     */
    private void collectState(JsonNode event, Map<String, Boolean> userStates, Map<String, Boolean> groupStates) {
        if (event == null || !event.isObject()) {
            return;
        }
        JsonNode source = event.path("source");
        if (!source.isObject()) {
            return;
        }

        String eventType = event.path("type").asText("").trim().toLowerCase(Locale.ROOT);
        String sourceType = source.path("type").asText("").trim().toLowerCase(Locale.ROOT);
        String userId = source.path("userId").asText("").trim();
        String groupId = source.path("groupId").asText("").trim();
        String roomId = source.path("roomId").asText("").trim();

        // Any event carrying a userId is enough evidence that the user can be
        // considered active unless a later event in the same batch says otherwise.
        if (!userId.isEmpty()) {
            userStates.putIfAbsent(userId, true);
        }

        // Room IDs are stored through the same group table because LINE push
        // targets behave similarly for this service's purposes.
        String groupOrRoomId = !groupId.isEmpty() ? groupId : roomId;
        if (!groupOrRoomId.isEmpty()) {
            groupStates.putIfAbsent(groupOrRoomId, true);
        }

        int joinedUserCount = 0;
        if ("memberjoined".equals(eventType)) {
            JsonNode members = event.path("joined").path("members");
            if (members.isArray()) {
                for (JsonNode member : members) {
                    String joinedUserId = member.path("userId").asText("").trim();
                    if (joinedUserId.isEmpty()) continue;
                    userStates.put(joinedUserId, true);
                    joinedUserCount++;
                }
            }
        }

        switch (eventType) {
            case "join", "memberjoined" -> {
                if (!groupOrRoomId.isEmpty()) {
                    groupStates.put(groupOrRoomId, true);
                }
                log.info("[LINE_GROUP_JOIN] event_type={} source_type={} group_present={} user_present={} joined_user_count={}",
                        dash(eventType), dash(sourceType), present(groupOrRoomId),
                        present(userId), joinedUserCount);
            }
            case "leave" -> {
                if (!groupOrRoomId.isEmpty()) {
                    groupStates.put(groupOrRoomId, false);
                }
                log.info("[LINE_GROUP_LEAVE] source_type={} group_present={} user_present={}",
                        dash(sourceType), present(groupOrRoomId), present(userId));
            }
            case "unfollow" -> {
                if (!userId.isEmpty()) {
                    userStates.put(userId, false);
                }
                log.info("[LINE_USER_UNFOLLOW] user_present={}", present(userId));
            }
            case "follow" -> {
                if (!userId.isEmpty()) {
                    userStates.put(userId, true);
                }
                log.info("[LINE_USER_FOLLOW] user_present={}", present(userId));
            }
            case "message" -> {
                String msgType = event.path("message").path("type").asText("");
                String text = event.path("message").path("text").asText("");
                String messageId = event.path("message").path("id").asText("");
                log.info("line_message_received source_type={} source_present={} user_present={} message_present={} msg_type={} text_chars={}",
                        dash(sourceType), present(userId.isEmpty() ? groupOrRoomId : userId),
                        present(userId), present(messageId), dash(msgType), text.length());
            }
            case "memberleft" -> log.info("event=memberLeft source_type={} source_present={} left_member_count={}",
                    dash(sourceType), present(groupOrRoomId), memberCount(event.path("left").path("members")));
            default -> log.info("event={} source_type={} source_present={} user_present={}",
                    dash(eventType), dash(sourceType), present(groupOrRoomId.isEmpty() ? userId : groupOrRoomId),
                    present(userId));
        }
    }

    /**
     * Applies runtime text commands. Matching by prefix allows operators to add
     * short notes after the command without breaking recognition.
     */
    private boolean handleCommand(JsonNode event) {
        if (event == null || !event.isObject()) {
            return false;
        }
        String eventType = event.path("type").asText("").trim().toLowerCase(Locale.ROOT);
        if (!"message".equals(eventType)) {
            return false;
        }
        JsonNode message = event.path("message");
        String msgType = message.path("type").asText("").trim().toLowerCase(Locale.ROOT);
        if (!"text".equals(msgType)) {
            return false;
        }
        String text = message.path("text").asText("").trim();
        if (text.startsWith(TEST_MODE_PREFIX)) {
            log.info("line_message_command matched=test_mode prefix={}", TEST_MODE_PREFIX);
            pushModeService.enableTestMode("line_webhook");
            return true;
        }
        if (text.startsWith(DISABLE_PUSH_PREFIX)) {
            log.info("line_message_command matched=disable_push prefix={}", DISABLE_PUSH_PREFIX);
            pushModeService.disableAllPushes("line_webhook");
            return true;
        }
        if (text.startsWith(PUSH_LATEST_TEST_PREFIX)) {
            log.info("line_message_command matched=push_latest_test prefix={}", PUSH_LATEST_TEST_PREFIX);
            if (poller == null) {
                log.warn("manual_market_analysis_push skipped reason=poller_unavailable");
            } else {
                poller.pushLatestToTestAccountsNow();
            }
            return true;
        }
        return false;
    }

    /**
     * Keeps log fields readable when LINE omits an ID.
     */
    private static String dash(String v) {
        return v == null || v.isEmpty() ? "-" : v;
    }

    private static boolean present(String v) {
        return v != null && !v.isEmpty();
    }

    private static int memberCount(JsonNode members) {
        return members != null && members.isArray() ? members.size() : 0;
    }

    /**
     * Batch processing summary returned to the webhook controller for observable
     * response counts and tests.
     */
    public record Summary(
            int events,
            int users,
            int groups,
            int activeUsers,
            int inactiveUsers,
            int activeGroups,
            int inactiveGroups,
            int commands
    ) {}
}
