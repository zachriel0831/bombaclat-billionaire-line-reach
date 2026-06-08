package com.zack.linerelay.macro;

import com.zack.linerelay.db.BotTarget;
import com.zack.linerelay.db.BotTargetRepository;
import com.zack.linerelay.db.MacroRelease;
import com.zack.linerelay.db.MacroReleaseCalendarRepository;
import com.zack.linerelay.push.LinePushClient;
import com.zack.linerelay.push.PushMessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Sends one aggregated LINE reminder for tomorrow's official U.S. macro data.
 */
@Service
@ConditionalOnProperty(prefix = "line.mysql", name = "enabled", havingValue = "true")
public class MacroCalendarReminderService {

    private static final Logger log = LoggerFactory.getLogger(MacroCalendarReminderService.class);
    private static final DateTimeFormatter RELEASE_TIME = DateTimeFormatter.ofPattern("MM/dd HH:mm");

    private final MacroReleaseCalendarRepository releaseRepo;
    private final BotTargetRepository targetRepo;
    private final LinePushClient pushClient;

    public MacroCalendarReminderService(
            MacroReleaseCalendarRepository releaseRepo,
            BotTargetRepository targetRepo,
            LinePushClient pushClient
    ) {
        this.releaseRepo = releaseRepo;
        this.targetRepo = targetRepo;
        this.pushClient = pushClient;
    }

    public ReminderResult sendTomorrowReminders(LocalDate reminderDateTaipei) {
        List<MacroRelease> releases = releaseRepo.findPendingForReminderDate(reminderDateTaipei);
        if (releases.isEmpty()) {
            log.info("macro_calendar_reminder skipped reason=no_due_releases reminder_date={}", reminderDateTaipei);
            return new ReminderResult(false, reminderDateTaipei, 0, 0, 0, 0, "no_due_releases");
        }

        List<BotTarget> targets = targetRepo.listActiveTargets();
        if (targets.isEmpty()) {
            log.warn("macro_calendar_reminder skipped reason=no_targets reminder_date={} releases={}",
                    reminderDateTaipei, releases.size());
            releaseRepo.markReminderFailed(releaseIds(releases), "no_targets", "No active LINE targets resolved");
            return new ReminderResult(false, reminderDateTaipei, releases.size(), 0, 0, 0, "no_targets");
        }

        String message = buildMessage(reminderDateTaipei, releases);
        int pushed = 0;
        int skippedByToggle = 0;
        int skippedByRateLimit = 0;
        for (BotTarget target : targets) {
            try {
                LinePushClient.PushAttempt attempt = pushClient.push(PushMessageType.MACRO_CALENDAR, target.id(), message);
                if (attempt.delivered()) {
                    pushed++;
                } else if (attempt.skippedByToggle()) {
                    skippedByToggle++;
                } else if (attempt.skippedByRateLimit()) {
                    skippedByRateLimit++;
                }
            } catch (Exception ex) {
                log.error("macro_calendar_reminder push_failed target_type={} target_id={} err={}",
                        target.type(), target.id(), ex.getMessage());
            }
        }

        if (pushed > 0) {
            int rows = releaseRepo.markReminderPushed(releaseIds(releases));
            log.info("macro_calendar_reminder marked_pushed releases={} rows={}", releases.size(), rows);
        } else if (skippedByRateLimit > 0 || skippedByToggle > 0) {
            releaseRepo.markReminderFailed(releaseIds(releases), "not_delivered",
                    "No successful delivery; skippedByToggle=" + skippedByToggle
                            + ", skippedByRateLimit=" + skippedByRateLimit);
        }

        log.info("macro_calendar_reminder finished reminder_date={} releases={} targets={} pushed={} skipped_by_toggle={} skipped_by_rate_limit={}",
                reminderDateTaipei, releases.size(), targets.size(), pushed, skippedByToggle, skippedByRateLimit);
        return new ReminderResult(true, reminderDateTaipei, releases.size(), pushed, skippedByToggle,
                skippedByRateLimit, null);
    }

    String buildMessage(LocalDate reminderDateTaipei, List<MacroRelease> releases) {
        LocalDate releaseDate = releases.stream()
                .filter(item -> item.releaseAtTaipei() != null)
                .map(item -> item.releaseAtTaipei().toLocalDate())
                .findFirst()
                .orElse(reminderDateTaipei.plusDays(1));
        List<MacroRelease> macroReleases = releases.stream()
                .filter(item -> !item.earningsRelease())
                .toList();
        List<MacroRelease> earningsReleases = releases.stream()
                .filter(MacroRelease::earningsRelease)
                .toList();
        StringBuilder sb = new StringBuilder();
        if (!macroReleases.isEmpty() && !earningsReleases.isEmpty()) {
            sb.append("明天有重要市場行事曆\n");
            sb.append("發布日：以各項目台灣時間為準\n\n");
        } else if (!earningsReleases.isEmpty()) {
            sb.append("明天有權值股財報公布\n");
            sb.append("發布日：").append(releaseDate).append("（台灣時間）\n\n");
        } else {
            sb.append("明天有美國重要經濟數據公布\n");
            sb.append("發布日：").append(releaseDate).append("（台灣時間）\n\n");
        }
        appendSection(sb, "美國經濟數據", macroReleases);
        appendSection(sb, "權值股財報", earningsReleases);
        sb.append("\n資料來源：");
        sb.append(releases.stream().map(MacroRelease::sourceName).distinct().reduce((a, b) -> a + " / " + b).orElse("Official calendar"));
        return sb.toString().strip();
    }

    private void appendSection(StringBuilder sb, String title, List<MacroRelease> releases) {
        if (releases.isEmpty()) {
            return;
        }
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
            sb.append("\n");
        }
        sb.append(title).append("\n");
        for (int i = 0; i < releases.size(); i++) {
            MacroRelease release = releases.get(i);
            sb.append(i + 1).append(". ");
            if (release.releaseAtTaipei() != null) {
                sb.append(release.releaseAtTaipei().format(RELEASE_TIME)).append(" ");
            }
            sb.append(release.indicatorName());
            if (release.periodLabel() != null && !release.periodLabel().isBlank()) {
                sb.append("（").append(release.periodLabel()).append("）");
            }
            sb.append("\n");
            sb.append(noteFor(release)).append("\n");
            if (i < releases.size() - 1) {
                sb.append("\n");
            }
        }
        sb.append("\n");
    }

    private String noteFor(MacroRelease release) {
        if (release.earningsRelease()) {
            return "觀察：財報與展望是否牽動美股科技權值、AI 供應鏈與台股相關族群。";
        }
        return noteFor(release.indicatorCode());
    }

    private String noteFor(String indicatorCode) {
        return switch (indicatorCode == null ? "" : indicatorCode) {
            case "us_cpi" -> "觀察：是否降息、科技股估值。高於預期：AI 股可能修正；低於預期：AI / 科技股偏利多。";
            case "us_ppi" -> "觀察：企業成本壓力與 CPI 傳導。高於預期：利率預期偏鷹；低於預期：成本壓力降溫。";
            case "us_nonfarm_payrolls" -> "觀察：就業是否過熱與薪資壓力。強於預期：降息預期降溫；弱於預期：利率壓力下降但留意景氣風險。";
            case "us_retail_sales" -> "觀察：美國消費力。高於預期：消費韌性強、利率可能偏高；低於預期：消費降溫。";
            default -> "觀察：是否改變 Fed 路徑、美元利率與科技股估值。";
        };
    }

    private List<Long> releaseIds(List<MacroRelease> releases) {
        return releases.stream().map(MacroRelease::id).toList();
    }

    public record ReminderResult(
            boolean ok,
            LocalDate reminderDateTaipei,
            int releases,
            int pushed,
            int skippedByToggle,
            int skippedByRateLimit,
            String skipReason
    ) {
    }
}
