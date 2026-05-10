package com.zack.linerelay.market;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that each scheduled method delegates to the intended analysis slot.
 */
class MarketAnalysisSchedulerTest {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private MarketAnalysisScheduler scheduler(MarketAnalysisPoller poller, String instant) {
        return new MarketAnalysisScheduler(poller, Clock.fixed(Instant.parse(instant), TAIPEI));
    }

    private MarketAnalysisScheduler scheduler(MarketAnalysisPoller poller, String instant, String holidays) {
        return new MarketAnalysisScheduler(poller, Clock.fixed(Instant.parse(instant), TAIPEI), holidays);
    }

    private MarketAnalysisScheduler scheduler(
            MarketAnalysisPoller poller,
            String instant,
            String twHolidays,
            String usHolidays
    ) {
        return new MarketAnalysisScheduler(poller, Clock.fixed(Instant.parse(instant), TAIPEI), twHolidays, usHolidays);
    }

    @Test
    void pushPreTwOpenAnalysisPollsPreTwOpenSlot() {
        MarketAnalysisPoller poller = mock(MarketAnalysisPoller.class);
        when(poller.pollOnce(null, MarketAnalysisScheduler.PRE_TW_OPEN_SLOT))
                .thenReturn(new MarketAnalysisPoller.PollResult(true, "2026-04-21", "pre_tw_open", 1, 0, 0, true, null, 43L));
        MarketAnalysisScheduler scheduler = scheduler(poller, "2026-04-20T16:00:00Z");

        scheduler.pushPreTwOpenAnalysis();

        verify(poller).pollOnce(null, MarketAnalysisScheduler.PRE_TW_OPEN_SLOT);
    }

    @Test
    void pushPreTwOpenAnalysisSkipsWeekend() {
        MarketAnalysisPoller poller = mock(MarketAnalysisPoller.class);
        MarketAnalysisScheduler scheduler = scheduler(poller, "2026-04-25T16:00:00Z");

        scheduler.pushPreTwOpenAnalysis();

        verify(poller, never()).pollOnce(null, MarketAnalysisScheduler.PRE_TW_OPEN_SLOT);
    }

    @Test
    void pushPreTwOpenAnalysisPollsUsCloseOnConfiguredTwMarketHolidayWhenUsOpen() {
        MarketAnalysisPoller poller = mock(MarketAnalysisPoller.class);
        when(poller.pollOnce(null, MarketAnalysisScheduler.US_CLOSE_SLOT))
                .thenReturn(new MarketAnalysisPoller.PollResult(true, "2026-05-01", "us_close", 1, 0, 0, true, null, 46L));
        MarketAnalysisScheduler scheduler = scheduler(poller, "2026-05-01T00:30:00Z", "2026-05-01", "");

        scheduler.pushPreTwOpenAnalysis();

        verify(poller).pollOnce(null, MarketAnalysisScheduler.US_CLOSE_SLOT);
        verify(poller, never()).pollOnce(null, MarketAnalysisScheduler.PRE_TW_OPEN_SLOT);
    }

    @Test
    void pushPreTwOpenAnalysisPollsPreTwOpenWhenTwOpenAndUsClosed() {
        MarketAnalysisPoller poller = mock(MarketAnalysisPoller.class);
        when(poller.pollOnce(null, MarketAnalysisScheduler.PRE_TW_OPEN_SLOT))
                .thenReturn(new MarketAnalysisPoller.PollResult(true, "2026-09-08", "pre_tw_open", 1, 0, 0, true, null, 47L));
        MarketAnalysisScheduler scheduler = scheduler(poller, "2026-09-08T00:30:00Z", "", "2026-09-07");

        scheduler.pushPreTwOpenAnalysis();

        verify(poller).pollOnce(null, MarketAnalysisScheduler.PRE_TW_OPEN_SLOT);
        verify(poller, never()).pollOnce(null, MarketAnalysisScheduler.US_CLOSE_SLOT);
    }

    @Test
    void pushPreTwOpenAnalysisPollsMacroDailyWhenBothMarketsClosed() {
        MarketAnalysisPoller poller = mock(MarketAnalysisPoller.class);
        when(poller.pollOnce(null, MarketAnalysisScheduler.MACRO_DAILY_SLOT))
                .thenReturn(new MarketAnalysisPoller.PollResult(true, "2026-04-06", "macro_daily", 1, 0, 0, true, null, 48L));
        MarketAnalysisScheduler scheduler = scheduler(poller, "2026-04-06T00:30:00Z", "2026-04-06", "");

        scheduler.pushPreTwOpenAnalysis();

        verify(poller).pollOnce(null, MarketAnalysisScheduler.MACRO_DAILY_SLOT);
        verify(poller, never()).pollOnce(null, MarketAnalysisScheduler.US_CLOSE_SLOT);
        verify(poller, never()).pollOnce(null, MarketAnalysisScheduler.PRE_TW_OPEN_SLOT);
    }

    @Test
    void pushPreTwOpenAnalysisSkipsNormalSaturdayBecauseUsCloseJobOwnsIt() {
        MarketAnalysisPoller poller = mock(MarketAnalysisPoller.class);
        MarketAnalysisScheduler scheduler = scheduler(poller, "2026-04-24T16:00:00Z", "", "");

        scheduler.pushPreTwOpenAnalysis();

        verify(poller, never()).pollOnce(null, MarketAnalysisScheduler.US_CLOSE_SLOT);
        verify(poller, never()).pollOnce(null, MarketAnalysisScheduler.PRE_TW_OPEN_SLOT);
        verify(poller, never()).pollOnce(null, MarketAnalysisScheduler.MACRO_DAILY_SLOT);
    }

    @Test
    void pushPreTwOpenAnalysisPollsMacroDailyOnSaturdayWhenFridayUsMarketClosed() {
        MarketAnalysisPoller poller = mock(MarketAnalysisPoller.class);
        when(poller.pollOnce(null, MarketAnalysisScheduler.MACRO_DAILY_SLOT))
                .thenReturn(new MarketAnalysisPoller.PollResult(true, "2026-07-04", "macro_daily", 1, 0, 0, true, null, 49L));
        MarketAnalysisScheduler scheduler = scheduler(poller, "2026-07-03T16:00:00Z", "", "2026-07-03");

        scheduler.pushPreTwOpenAnalysis();

        verify(poller).pollOnce(null, MarketAnalysisScheduler.MACRO_DAILY_SLOT);
        verify(poller, never()).pollOnce(null, MarketAnalysisScheduler.US_CLOSE_SLOT);
    }

    @Test
    void pushSaturdayUsCloseAnalysisPollsUsCloseSlotOnSaturday() {
        MarketAnalysisPoller poller = mock(MarketAnalysisPoller.class);
        when(poller.pollOnce(null, MarketAnalysisScheduler.US_CLOSE_SLOT))
                .thenReturn(new MarketAnalysisPoller.PollResult(true, "2026-04-25", "us_close", 1, 0, 0, true, null, 44L));
        MarketAnalysisScheduler scheduler = scheduler(poller, "2026-04-24T16:00:00Z");

        scheduler.pushSaturdayUsCloseAnalysis();

        verify(poller).pollOnce(null, MarketAnalysisScheduler.US_CLOSE_SLOT);
    }

    @Test
    void pushSaturdayUsCloseAnalysisSkipsWhenNotSaturday() {
        MarketAnalysisPoller poller = mock(MarketAnalysisPoller.class);
        MarketAnalysisScheduler scheduler = scheduler(poller, "2026-04-26T16:00:00Z");

        scheduler.pushSaturdayUsCloseAnalysis();

        verify(poller, never()).pollOnce(null, MarketAnalysisScheduler.US_CLOSE_SLOT);
    }

    @Test
    void pushSaturdayUsCloseAnalysisSkipsWhenFridayUsMarketClosed() {
        MarketAnalysisPoller poller = mock(MarketAnalysisPoller.class);
        MarketAnalysisScheduler scheduler = scheduler(poller, "2026-07-03T16:00:00Z", "", "2026-07-03");

        scheduler.pushSaturdayUsCloseAnalysis();

        verify(poller, never()).pollOnce(null, MarketAnalysisScheduler.US_CLOSE_SLOT);
    }

    @Test
    void pushSundayWeeklyTwPreopenAnalysisPollsWeeklySlotOnSunday() {
        MarketAnalysisPoller poller = mock(MarketAnalysisPoller.class);
        when(poller.pollOnce(null, MarketAnalysisScheduler.WEEKLY_TW_PREOPEN_SLOT))
                .thenReturn(new MarketAnalysisPoller.PollResult(true, "2026-04-26", "weekly_tw_preopen", 1, 0, 0, true, null, 45L));
        MarketAnalysisScheduler scheduler = scheduler(poller, "2026-04-25T16:00:00Z");

        scheduler.pushSundayWeeklyTwPreopenAnalysis();

        verify(poller).pollOnce(null, MarketAnalysisScheduler.WEEKLY_TW_PREOPEN_SLOT);
    }

    @Test
    void pushSundayWeeklyTwPreopenAnalysisSkipsWhenNotSunday() {
        MarketAnalysisPoller poller = mock(MarketAnalysisPoller.class);
        MarketAnalysisScheduler scheduler = scheduler(poller, "2026-04-24T16:00:00Z");

        scheduler.pushSundayWeeklyTwPreopenAnalysis();

        verify(poller, never()).pollOnce(null, MarketAnalysisScheduler.WEEKLY_TW_PREOPEN_SLOT);
    }

    @Test
    void disableStaleUnpushedAnalysesUsesCurrentTaipeiDate() {
        MarketAnalysisPoller poller = mock(MarketAnalysisPoller.class);
        when(poller.disableStaleUnpushedBefore("2026-04-21"))
                .thenReturn(new MarketAnalysisPoller.CleanupResult("2026-04-21", 3));
        MarketAnalysisScheduler scheduler = scheduler(poller, "2026-04-20T16:00:00Z");

        scheduler.disableStaleUnpushedAnalyses();

        verify(poller).disableStaleUnpushedBefore("2026-04-21");
    }
}
