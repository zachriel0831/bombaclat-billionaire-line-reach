package com.zack.linerelay.market;

import com.zack.linerelay.db.BotTarget;
import com.zack.linerelay.db.BotTargetRepository;
import com.zack.linerelay.db.MarketAnalysis;
import com.zack.linerelay.db.MarketAnalysisRepository;
import com.zack.linerelay.push.LinePushClient;
import com.zack.linerelay.push.PushMessageType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Orchestrates the core flow: fetch an analysis row, resolve targets, render a
 * LINE-safe message, and send it through the push client.
 */
@Service
@ConditionalOnProperty(prefix = "line.mysql", name = "enabled", havingValue = "true")
public class MarketAnalysisPoller {

    private static final Logger log = LoggerFactory.getLogger(MarketAnalysisPoller.class);

    public static final String DEFAULT_SLOT = "pre_tw_open";
    private static final int MAX_MESSAGE_LENGTH = 4500;
    private static final int SUMMARY_EXCERPT_CODE_POINTS = 100;
    private static final String GARBLED_SUMMARY_REASON = "garbled_summary";
    private static final String TODAY_ONE_SENTENCE_LABEL = "今日一句話";
    private static final String READ_MORE_LABEL = "看完整分析：";

    private final MarketAnalysisRepository analysisRepo;
    private final BotTargetRepository targetRepo;
    private final LinePushClient pushClient;
    private final String publicAnalysisBaseUrl;

    @Autowired
    public MarketAnalysisPoller(
            MarketAnalysisRepository analysisRepo,
            BotTargetRepository targetRepo,
            LinePushClient pushClient,
            @Value("${line.public-analysis-base-url:}") String publicAnalysisBaseUrl
    ) {
        this.analysisRepo = analysisRepo;
        this.targetRepo = targetRepo;
        this.pushClient = pushClient;
        this.publicAnalysisBaseUrl = normalizeBaseUrl(publicAnalysisBaseUrl);
    }

    MarketAnalysisPoller(
            MarketAnalysisRepository analysisRepo,
            BotTargetRepository targetRepo,
            LinePushClient pushClient
    ) {
        this(analysisRepo, targetRepo, pushClient, "");
    }

    /**
     * Main market-analysis push workflow used by both admin calls and scheduled
     * jobs. The optional parameters let callers test a specific date/slot while
     * scheduled jobs pass null for today's date.
     */
    public PollResult pollOnce(String analysisDate, String analysisSlot) {
        String date = (analysisDate != null && !analysisDate.isBlank())
                ? analysisDate
                : LocalDate.now(ZoneId.systemDefault()).toString();
        String slot = (analysisSlot != null && !analysisSlot.isBlank()) ? analysisSlot : DEFAULT_SLOT;

        Optional<MarketAnalysis> opt = analysisRepo.findLatest(date, slot);
        if (opt.isEmpty()) {
            log.warn("poll_market_analysis skipped reason=no_analysis date={} slot={}", date, slot);
            return new PollResult(false, date, slot, 0, 0, 0, false, "no_analysis", null);
        }
        return pushResolvedAnalysis(opt.get(), true, "poll_market_analysis");
    }

    /**
     * Retry path for summaries that should have been sent earlier the same day
     * but still have `pushed = 0`. Kept as a small utility for manual/admin
     * retry flows; no scheduled job calls it now that daily push is pre-open only.
     */
    public PollResult pollUnpushedOnce(String analysisDate, String analysisSlot) {
        String date = (analysisDate != null && !analysisDate.isBlank())
                ? analysisDate
                : LocalDate.now(ZoneId.systemDefault()).toString();
        String slot = (analysisSlot != null && !analysisSlot.isBlank()) ? analysisSlot : DEFAULT_SLOT;

        Optional<MarketAnalysis> opt = analysisRepo.findLatestUnpushed(date, slot);
        if (opt.isEmpty()) {
            log.info("poll_unpushed_market_analysis skipped reason=no_pending_analysis date={} slot={}", date, slot);
            return new PollResult(false, date, slot, 0, 0, 0, pushClient.isPushEnabled(), "no_pending_analysis", null);
        }
        return pushResolvedAnalysis(opt.get(), true, "poll_unpushed_market_analysis");
    }

    /**
     * Manual safety path for the `西卡卡推送` LINE command. It intentionally
     * bypasses the master push toggle but still restricts delivery to active
     * test users and does not update any pushed/queue state.
     */
    public PollResult pushLatestToTestAccountsNow() {
        Optional<MarketAnalysis> opt = analysisRepo.findLatestAny();
        if (opt.isEmpty()) {
            log.warn("manual_market_analysis_push skipped reason=no_analysis");
            return new PollResult(false, null, null, 0, 0, 0, true, "no_analysis", null);
        }

        MarketAnalysis analysis = opt.get();
        if (isLikelyGarbledSummary(analysis.summaryText())) {
            int summaryLen = analysis.summaryText() == null ? 0 : analysis.summaryText().length();
            log.error("manual_market_analysis_push skipped reason={} analysis_id={} date={} slot={} summary_chars={}",
                    GARBLED_SUMMARY_REASON, analysis.id(), analysis.analysisDate(), analysis.analysisSlot(), summaryLen);
            return new PollResult(false, analysis.analysisDate(), analysis.analysisSlot(),
                    0, 0, 0, true, GARBLED_SUMMARY_REASON, analysis.id());
        }

        List<String> testUserIds = targetRepo.listActiveTestUserIds();
        log.info("manual_market_analysis_push fetched id={} date={} slot={} test_users={}",
                analysis.id(), analysis.analysisDate(), analysis.analysisSlot(), testUserIds.size());

        if (testUserIds.isEmpty()) {
            log.warn("manual_market_analysis_push no_test_targets analysis_id={}", analysis.id());
            return new PollResult(false, analysis.analysisDate(), analysis.analysisSlot(),
                    0, 0, 0, true, "no_test_targets", analysis.id());
        }

        String message = buildMessage(analysis);
        int pushed = 0;
        int skippedByRateLimit = 0;
        for (String userId : testUserIds) {
            try {
                LinePushClient.PushAttempt attempt = pushClient.pushIgnoringToggle(
                        PushMessageType.PUBLIC_ANALYSIS, userId, message);
                if (attempt.delivered()) {
                    pushed++;
                } else if (attempt.skippedByRateLimit()) {
                    skippedByRateLimit++;
                }
            } catch (Exception ex) {
                // Manual testing should report partial failures but keep trying
                // remaining test accounts.
                log.error("manual_market_analysis_push failed user_id={} analysis_id={} err={}",
                        userId, analysis.id(), ex.getMessage());
            }
        }

        log.info("manual_market_analysis_push done analysis_id={} test_users={} pushed={} skipped_by_rate_limit={} pushed_state_updated=false",
                analysis.id(), testUserIds.size(), pushed, skippedByRateLimit);
        return new PollResult(true, analysis.analysisDate(), analysis.analysisSlot(),
                pushed, 0, skippedByRateLimit, true, null, analysis.id());
    }

    /**
     * Disables stale rows that were not pushed before a new Taipei business day
     * starts. This prevents delayed jobs from sending old analysis text.
     */
    public CleanupResult disableStaleUnpushedBefore(String beforeAnalysisDate) {
        int disabled = analysisRepo.disableUnpushedBefore(beforeAnalysisDate);
        log.info("disable_stale_unpushed_analyses before_date={} disabled={}", beforeAnalysisDate, disabled);
        return new CleanupResult(beforeAnalysisDate, disabled);
    }

    private PollResult pushResolvedAnalysis(MarketAnalysis analysis, boolean markPushedOnSuccess, String logPrefix) {
        // Log metadata, not the full summary, so production logs stay readable.
        int summaryLen = analysis.summaryText() == null ? 0 : analysis.summaryText().length();
        log.info("{} fetched id={} date={} slot={} model={} prompt_version={} summary_chars={} updated_at={}",
                logPrefix, analysis.id(), analysis.analysisDate(), analysis.analysisSlot(),
                analysis.model(), analysis.promptVersion(), summaryLen, analysis.updatedAt());

        if (isLikelyGarbledSummary(analysis.summaryText())) {
            log.error("{} skipped reason={} analysis_id={} date={} slot={} summary_chars={}",
                    logPrefix, GARBLED_SUMMARY_REASON, analysis.id(), analysis.analysisDate(),
                    analysis.analysisSlot(), summaryLen);
            return new PollResult(false, analysis.analysisDate(), analysis.analysisSlot(),
                    0, 0, 0, pushClient.isPushEnabled(), GARBLED_SUMMARY_REASON, analysis.id());
        }

        List<BotTarget> targets = targetRepo.listActiveTargets();
        // Target resolution is logged before push so delivery issues can be
        // separated from DB/test-mode filtering issues.
        log.info("{} resolved_targets total={} groups={} users={}",
                logPrefix, targets.size(),
                targets.stream().filter(t -> BotTarget.TYPE_GROUP.equals(t.type())).count(),
                targets.stream().filter(t -> BotTarget.TYPE_USER.equals(t.type())).count());
        for (BotTarget t : targets) {
            log.info("{} target type={} id={}", logPrefix, t.type(), t.id());
        }

        if (targets.isEmpty()) {
            log.warn("{} no_targets date={} slot={}", logPrefix, analysis.analysisDate(), analysis.analysisSlot());
            return new PollResult(false, analysis.analysisDate(), analysis.analysisSlot(),
                    0, 0, 0, pushClient.isPushEnabled(), "no_targets", analysis.id());
        }

        String message = buildMessage(analysis);
        boolean pushEnabled = pushClient.isPushEnabled();
        int pushed = 0;
        int skipped = 0;
        int skippedByRateLimit = 0;

        // Keep the dry-run path explicit: it should resolve data and targets but
        // never call the LINE API while the master toggle is off.
        if (!pushEnabled) {
            log.info("{} push_toggle=OFF skipping {} target(s). message_preview={}",
                    logPrefix, targets.size(), preview(message));
            skipped = targets.size();
        } else {
            for (BotTarget t : targets) {
                try {
                    LinePushClient.PushAttempt attempt = pushClient.push(
                            PushMessageType.PUBLIC_ANALYSIS, t.id(), message);
                    if (attempt.delivered()) {
                        pushed++;
                    } else if (attempt.skippedByToggle()) {
                        skipped++;
                    } else if (attempt.skippedByRateLimit()) {
                        skippedByRateLimit++;
                    }
                } catch (Exception ex) {
                    // One failed target should not prevent delivery to the rest.
                    log.error("{} push_failed target_type={} target_id={} err={}",
                            logPrefix, t.type(), t.id(), ex.getMessage());
                }
            }
        }

        if (markPushedOnSuccess && pushed > 0) {
            int rows = analysisRepo.markPushed(analysis.id());
            log.info("{} marked_pushed analysis_id={} rows={}", logPrefix, analysis.id(), rows);
        }

        log.info("{} done analysis_id={} targets={} pushed={} skipped_by_toggle={} skipped_by_rate_limit={}",
                logPrefix, analysis.id(), targets.size(), pushed, skipped, skippedByRateLimit);

        return new PollResult(true, analysis.analysisDate(), analysis.analysisSlot(),
                pushed, skipped, skippedByRateLimit, pushEnabled, null, analysis.id());
    }

    /**
     * Builds a LINE-safe text payload. The limit is set below LINE's 5000-char
     * hard cap to leave room for future labels or metadata.
     */
    private String buildMessage(MarketAnalysis a) {
        String detailUrl = analysisDetailUrl(a);
        if (!detailUrl.isBlank()) {
            String excerpt = buildExcerpt(a.summaryText());
            return (a.analysisDate() + " 市場分析\n"
                    + excerpt + "\n\n"
                    + READ_MORE_LABEL + "\n"
                    + detailUrl).strip();
        }

        String header = a.analysisDate();
        String body = (a.summaryText() == null ? "" : a.summaryText()).strip();
        String combined = (header + "\n" + body).strip();
        return combined.length() > MAX_MESSAGE_LENGTH
                ? combined.substring(0, MAX_MESSAGE_LENGTH)
                : combined;
    }

    private String buildExcerpt(String summaryText) {
        String body = summaryText == null ? "" : summaryText.strip();
        if (body.isBlank()) {
            return "今日市場分析已更新...";
        }

        String[] paragraphs = body.replace("\r\n", "\n").replace('\r', '\n').split("\\n\\s*\\n");
        String oneSentenceExcerpt = buildTodayOneSentenceExcerpt(paragraphs);
        if (!oneSentenceExcerpt.isBlank()) {
            return excerptWithMoreMarker(oneSentenceExcerpt, SUMMARY_EXCERPT_CODE_POINTS);
        }

        String fallback = "";
        for (String paragraph : paragraphs) {
            String normalized = paragraph.replaceAll("\\s+", " ").strip();
            if (normalized.isBlank()) {
                continue;
            }
            if (fallback.isBlank()) {
                fallback = normalized;
            }
            if (normalized.startsWith("本週重點") || normalized.startsWith("本周重點")) {
                return excerptWithMoreMarker(normalized, SUMMARY_EXCERPT_CODE_POINTS);
            }
        }
        return excerptWithMoreMarker(fallback, SUMMARY_EXCERPT_CODE_POINTS);
    }

    private String buildTodayOneSentenceExcerpt(String[] paragraphs) {
        for (int i = 0; i < paragraphs.length; i++) {
            String normalized = paragraphs[i].replaceAll("\\s+", " ").strip();
            if (normalized.isBlank()) {
                continue;
            }

            if (normalized.equals(TODAY_ONE_SENTENCE_LABEL)) {
                String next = nextNonHeadingParagraph(paragraphs, i + 1);
                return next.isBlank() ? normalized : TODAY_ONE_SENTENCE_LABEL + " " + next;
            }

            if (normalized.startsWith(TODAY_ONE_SENTENCE_LABEL + " ")) {
                return normalized;
            }
        }
        return "";
    }

    private String nextNonHeadingParagraph(String[] paragraphs, int startIndex) {
        for (int i = startIndex; i < paragraphs.length; i++) {
            String normalized = paragraphs[i].replaceAll("\\s+", " ").strip();
            if (normalized.isBlank() || isDailySectionHeading(normalized)) {
                continue;
            }
            return normalized;
        }
        return "";
    }

    private boolean isDailySectionHeading(String text) {
        return text.equals(TODAY_ONE_SENTENCE_LABEL)
                || text.equals("三個檢查點")
                || text.equals("總經與流動性")
                || text.equals("景氣循環")
                || text.equals("國際新聞傳導")
                || text.equals("產業板塊解析")
                || text.equals("風險與資料缺口");
    }

    private String excerptWithMoreMarker(String text, int limit) {
        if (text == null || text.isBlank()) {
            return "";
        }
        int codePoints = text.codePointCount(0, text.length());
        String excerpt = text;
        if (codePoints > limit) {
            int end = text.offsetByCodePoints(0, limit);
            excerpt = text.substring(0, end);
        }
        excerpt = excerpt.stripTrailing();
        return excerpt.endsWith("...") ? excerpt : excerpt + "...";
    }

    private String analysisDetailUrl(MarketAnalysis analysis) {
        if (publicAnalysisBaseUrl.isBlank()) {
            return "";
        }
        return publicAnalysisBaseUrl + "/" + analysis.id();
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        String normalized = baseUrl.strip();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String preview(String text) {
        if (text == null) return "";
        return text.length() > 120 ? text.substring(0, 120) + "..." : text;
    }

    private boolean isLikelyGarbledSummary(String summaryText) {
        if (summaryText == null || summaryText.isBlank()) {
            return false;
        }

        String text = summaryText.strip();
        if (text.length() < 40) {
            return false;
        }

        if (text.contains("????????")) {
            return true;
        }

        long suspiciousChars = text.chars()
                .filter(c -> c == '?' || c == '\uFFFD')
                .count();
        return suspiciousChars >= 20 && ((double) suspiciousChars / text.length()) >= 0.20;
    }

    /**
     * Small result DTO returned by admin endpoints and logged by schedulers.
     */
    public record PollResult(
            boolean ok,
            String analysisDate,
            String analysisSlot,
            int pushed,
            int skippedByToggle,
            int skippedByRateLimit,
            boolean pushEnabled,
            String skipReason,
            Long analysisId
    ) {}

    /**
     * Result DTO for the midnight stale-analysis cleanup.
     */
    public record CleanupResult(String beforeAnalysisDate, int disabled) {}
}
