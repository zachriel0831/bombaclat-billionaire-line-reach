package com.zack.linerelay.market;

import com.zack.linerelay.db.BotTarget;
import com.zack.linerelay.db.BotTargetRepository;
import com.zack.linerelay.db.MarketAnalysis;
import com.zack.linerelay.db.MarketAnalysisRepository;
import com.zack.linerelay.push.LinePushClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

@Service
@ConditionalOnProperty(prefix = "line.mysql", name = "enabled", havingValue = "true")
public class MarketAnalysisPoller {

    private static final Logger log = LoggerFactory.getLogger(MarketAnalysisPoller.class);

    public static final String DEFAULT_SLOT = "pre_tw_open";
    private static final int MAX_MESSAGE_LENGTH = 4500;

    private final MarketAnalysisRepository analysisRepo;
    private final BotTargetRepository targetRepo;
    private final LinePushClient pushClient;

    public MarketAnalysisPoller(
            MarketAnalysisRepository analysisRepo,
            BotTargetRepository targetRepo,
            LinePushClient pushClient
    ) {
        this.analysisRepo = analysisRepo;
        this.targetRepo = targetRepo;
        this.pushClient = pushClient;
    }

    public PollResult pollOnce(String analysisDate, String analysisSlot) {
        String date = (analysisDate != null && !analysisDate.isBlank())
                ? analysisDate
                : LocalDate.now(ZoneId.systemDefault()).toString();
        String slot = (analysisSlot != null && !analysisSlot.isBlank()) ? analysisSlot : DEFAULT_SLOT;

        Optional<MarketAnalysis> opt = analysisRepo.findLatest(date, slot);
        if (opt.isEmpty()) {
            log.warn("poll_market_analysis skipped reason=no_analysis date={} slot={}", date, slot);
            return new PollResult(false, date, slot, 0, 0, false, "no_analysis", null);
        }

        MarketAnalysis analysis = opt.get();
        int summaryLen = analysis.summaryText() == null ? 0 : analysis.summaryText().length();
        log.info("poll_market_analysis fetched id={} date={} slot={} model={} prompt_version={} summary_chars={} updated_at={}",
                analysis.id(), analysis.analysisDate(), analysis.analysisSlot(),
                analysis.model(), analysis.promptVersion(), summaryLen, analysis.updatedAt());

        List<BotTarget> targets = targetRepo.listActiveTargets();
        log.info("poll_market_analysis resolved_targets total={} groups={} users={}",
                targets.size(),
                targets.stream().filter(t -> BotTarget.TYPE_GROUP.equals(t.type())).count(),
                targets.stream().filter(t -> BotTarget.TYPE_USER.equals(t.type())).count());
        for (BotTarget t : targets) {
            log.info("poll_market_analysis target type={} id={}", t.type(), t.id());
        }

        if (targets.isEmpty()) {
            log.warn("poll_market_analysis no_targets date={} slot={}", date, slot);
            return new PollResult(false, analysis.analysisDate(), analysis.analysisSlot(),
                    0, 0, pushClient.isPushEnabled(), "no_targets", analysis.id());
        }

        String message = buildMessage(analysis);
        boolean pushEnabled = pushClient.isPushEnabled();
        int pushed = 0;
        int skipped = 0;

        if (!pushEnabled) {
            log.info("poll_market_analysis push_toggle=OFF skipping {} target(s). message_preview={}",
                    targets.size(), preview(message));
            skipped = targets.size();
        } else {
            for (BotTarget t : targets) {
                try {
                    pushClient.push(t.id(), message);
                    pushed++;
                } catch (Exception ex) {
                    log.error("poll_market_analysis push_failed target_type={} target_id={} err={}",
                            t.type(), t.id(), ex.getMessage());
                }
            }
        }

        log.info("poll_market_analysis done analysis_id={} targets={} pushed={} skipped_by_toggle={}",
                analysis.id(), targets.size(), pushed, skipped);

        return new PollResult(true, analysis.analysisDate(), analysis.analysisSlot(),
                pushed, skipped, pushEnabled, null, analysis.id());
    }

    private String buildMessage(MarketAnalysis a) {
        String header = "[Market Analysis] " + a.analysisDate() + " " + a.analysisSlot();
        String body = (a.summaryText() == null ? "" : a.summaryText()).strip();
        String combined = (header + "\n" + body).strip();
        return combined.length() > MAX_MESSAGE_LENGTH
                ? combined.substring(0, MAX_MESSAGE_LENGTH)
                : combined;
    }

    private String preview(String text) {
        if (text == null) return "";
        return text.length() > 120 ? text.substring(0, 120) + "..." : text;
    }

    public record PollResult(
            boolean ok,
            String analysisDate,
            String analysisSlot,
            int pushed,
            int skippedByToggle,
            boolean pushEnabled,
            String skipReason,
            Long analysisId
    ) {}
}
