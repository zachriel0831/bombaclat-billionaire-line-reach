package com.zack.linerelay.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Cron-based wrapper around {@link MarketAnalysisPoller}.
 */
@Service
@ConditionalOnBean(MarketAnalysisPoller.class)
@ConditionalOnProperty(prefix = "line.schedule", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MarketAnalysisScheduler {

    private static final Logger log = LoggerFactory.getLogger(MarketAnalysisScheduler.class);

    public static final String US_CLOSE_SLOT = "us_close";
    public static final String PRE_TW_OPEN_SLOT = "pre_tw_open";
    public static final String MACRO_DAILY_SLOT = "macro_daily";
    public static final String WEEKLY_TW_PREOPEN_SLOT = "weekly_tw_preopen";

    private final MarketAnalysisPoller poller;
    private final Clock scheduleClock;
    private final Set<LocalDate> twMarketHolidays;
    private final Set<LocalDate> usMarketHolidays;

    @Autowired
    public MarketAnalysisScheduler(
            MarketAnalysisPoller poller,
            @Value("${line.schedule.zone:Asia/Taipei}") String scheduleZone,
            @Value("${line.schedule.tw-market-holidays:}") String twMarketHolidays,
            @Value("${line.schedule.us-market-holidays:}") String usMarketHolidays
    ) {
        this(poller, Clock.system(ZoneId.of(scheduleZone)), twMarketHolidays, usMarketHolidays);
    }

    MarketAnalysisScheduler(MarketAnalysisPoller poller, Clock scheduleClock) {
        this(poller, scheduleClock, "", "");
    }

    MarketAnalysisScheduler(MarketAnalysisPoller poller, Clock scheduleClock, String twMarketHolidays) {
        this(poller, scheduleClock, twMarketHolidays, "");
    }

    MarketAnalysisScheduler(
            MarketAnalysisPoller poller,
            Clock scheduleClock,
            String twMarketHolidays,
            String usMarketHolidays
    ) {
        this.poller = poller;
        this.scheduleClock = scheduleClock;
        this.twMarketHolidays = parseHolidays(twMarketHolidays);
        this.usMarketHolidays = parseHolidays(usMarketHolidays);
    }

    /**
     * Daily public-analysis decision point.
     *
     * 中文邏輯：
     * - 週日只交給 weekly summary。
     * - TW 休市、對應美股收盤日有交易：推 `us_close`。
     * - US 休市、TW 有交易：仍推台股早盤 `pre_tw_open`，不要期待上游會帶 `us_close`。
     * - TW/US 都休市：推 `macro_daily`。
     */
    @Scheduled(cron = "${line.schedule.pre-tw-open-cron}", zone = "${line.schedule.zone}")
    public void pushPreTwOpenAnalysis() {
        MarketCalendarState state = resolveMarketCalendarState();
        DayOfWeek day = state.localDate().getDayOfWeek();
        if (day == DayOfWeek.SUNDAY) {
            log.info("scheduled_market_analysis_push skipped slot=pre_tw_open date={} reason=weekly_summary_only",
                    state.localDate());
            return;
        }

        if (day == DayOfWeek.SATURDAY && state.usTradingDay()) {
            log.info("scheduled_market_analysis_push skipped slot=pre_tw_open date={} reason=saturday_us_close_job_owns_delivery us_session_date={}",
                    state.localDate(), state.usCloseSessionDate());
            return;
        }

        if (!state.twTradingDay() && state.usTradingDay()) {
            log.info("scheduled_market_analysis_push rerouted from_slot=pre_tw_open to_slot=us_close date={} us_session_date={} reason=tw_closed_us_open",
                    state.localDate(), state.usCloseSessionDate());
            pushSlot(US_CLOSE_SLOT);
            return;
        }

        if (state.twTradingDay()) {
            log.info("scheduled_market_analysis_push resolved slot=pre_tw_open date={} us_trading_day={}",
                    state.localDate(), state.usTradingDay());
            pushSlot(PRE_TW_OPEN_SLOT);
            return;
        }

        log.info("scheduled_market_analysis_push rerouted from_slot=pre_tw_open to_slot=macro_daily date={} us_session_date={} reason=tw_closed_us_closed",
                state.localDate(), state.usCloseSessionDate());
        pushSlot(MACRO_DAILY_SLOT);
    }

    /**
     * Saturday Taiwan-time digest for Friday's US market close.
     *
     * 中文邏輯：如果台北週六對應的週五美股休市，就不能再推 `us_close`；
     * 這種 TW/US 都休市的情境交給早盤決策點改推 `macro_daily`。
     */
    @Scheduled(cron = "${line.schedule.us-close-cron}", zone = "${line.schedule.zone}")
    public void pushSaturdayUsCloseAnalysis() {
        MarketCalendarState state = resolveMarketCalendarState();
        DayOfWeek day = state.localDate().getDayOfWeek();
        if (day != DayOfWeek.SATURDAY) {
            log.info("scheduled_market_analysis_push skipped slot={} day={} expected_day={}",
                    US_CLOSE_SLOT, day, DayOfWeek.SATURDAY);
            return;
        }
        if (!state.usTradingDay()) {
            log.info("scheduled_market_analysis_push skipped slot=us_close date={} us_session_date={} reason=us_market_closed",
                    state.localDate(), state.usCloseSessionDate());
            return;
        }
        pushSlot(US_CLOSE_SLOT);
    }

    /**
     * Sunday Taiwan-time weekly summary.
     */
    @Scheduled(cron = "${line.schedule.weekly-tw-preopen-cron}", zone = "${line.schedule.zone}")
    public void pushSundayWeeklyTwPreopenAnalysis() {
        pushSlotOnlyOnDay(WEEKLY_TW_PREOPEN_SLOT, DayOfWeek.SUNDAY);
    }

    /**
     * Midnight cleanup retires stale unpushed rows from previous dates so they
     * cannot be selected by a later delayed push.
     */
    @Scheduled(cron = "${line.schedule.disable-stale-unpushed-cron}", zone = "${line.schedule.zone}")
    public void disableStaleUnpushedAnalyses() {
        String today = LocalDate.now(scheduleClock).toString();
        log.info("scheduled_disable_stale_unpushed started before_date={}", today);
        MarketAnalysisPoller.CleanupResult result = poller.disableStaleUnpushedBefore(today);
        log.info("scheduled_disable_stale_unpushed finished before_date={} disabled={}",
                result.beforeAnalysisDate(), result.disabled());
    }

    /**
     * Shared scheduled-job wrapper so all slots get consistent start/finish logs.
     */
    private void pushSlot(String slot) {
        log.info("scheduled_market_analysis_push started slot={}", slot);
        MarketAnalysisPoller.PollResult result = poller.pollOnce(null, slot);
        log.info("scheduled_market_analysis_push finished slot={} ok={} analysis_id={} pushed={} skipped_by_toggle={} skipped_by_rate_limit={} skip_reason={}",
                slot, result.ok(), result.analysisId(), result.pushed(), result.skippedByToggle(),
                result.skippedByRateLimit(), result.skipReason());
    }

    private void pushSlotOnlyOnDay(String slot, DayOfWeek expectedDay) {
        DayOfWeek day = LocalDate.now(scheduleClock).getDayOfWeek();
        if (day != expectedDay) {
            log.info("scheduled_market_analysis_push skipped slot={} day={} expected_day={}", slot, day, expectedDay);
            return;
        }
        pushSlot(slot);
    }

    private MarketCalendarState resolveMarketCalendarState() {
        LocalDate today = LocalDate.now(scheduleClock);
        // 中文：台北今天早上看到的 `us_close`，實際上是台北日期前一天的美股交易日。
        LocalDate usCloseSessionDate = today.minusDays(1);
        return new MarketCalendarState(
                today,
                usCloseSessionDate,
                isTwTradingDay(today),
                isUsTradingDay(usCloseSessionDate)
        );
    }

    private boolean isTwTradingDay(LocalDate day) {
        return day.getDayOfWeek() != DayOfWeek.SATURDAY
                && day.getDayOfWeek() != DayOfWeek.SUNDAY
                && !twMarketHolidays.contains(day);
    }

    private boolean isUsTradingDay(LocalDate day) {
        return day.getDayOfWeek() != DayOfWeek.SATURDAY
                && day.getDayOfWeek() != DayOfWeek.SUNDAY
                && !usMarketHolidays.contains(day);
    }

    private Set<LocalDate> parseHolidays(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(LocalDate::parse)
                .collect(Collectors.toUnmodifiableSet());
    }

    private record MarketCalendarState(
            LocalDate localDate,
            LocalDate usCloseSessionDate,
            boolean twTradingDay,
            boolean usTradingDay
    ) {
    }
}
