package com.zack.linerelay.market;

import com.zack.linerelay.db.BotTarget;
import com.zack.linerelay.db.BotTargetRepository;
import com.zack.linerelay.db.MarketAnalysis;
import com.zack.linerelay.db.MarketAnalysisRepository;
import com.zack.linerelay.push.LinePushClient;
import com.zack.linerelay.push.PushMessageType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the market-analysis orchestration layer, including dry-run,
 * partial failure, rate-limit skips, and manual test-push branches.
 */
class MarketAnalysisPollerTest {

    private static final String DATE = "2026-04-20";
    private static final String SLOT = "pre_tw_open";
    private static final String WEEKLY_SLOT = "weekly_tw_preopen";

    private MarketAnalysis sampleAnalysis() {
        return new MarketAnalysis(
                42L, DATE, SLOT, "07:30",
                "gpt-5", "v2",
                "Latest pre-open summary",
                "{\"k\":\"v\"}",
                Instant.parse("2026-04-20T07:45:00Z"));
    }

    private MarketAnalysis garbledAnalysis() {
        return new MarketAnalysis(
                77L, DATE, SLOT, "07:30",
                "codex-guard", "v1",
                "1) " + "?".repeat(80) + "\n" + "?".repeat(80),
                "{\"k\":\"v\"}",
                Instant.parse("2026-04-20T07:45:00Z"));
    }

    @Test
    void pollOnceReturnsNoAnalysisWhenMissing() {
        MarketAnalysisRepository analysisRepo = mock(MarketAnalysisRepository.class);
        BotTargetRepository targetRepo = mock(BotTargetRepository.class);
        LinePushClient pushClient = mock(LinePushClient.class);
        when(analysisRepo.findLatest(DATE, SLOT)).thenReturn(Optional.empty());

        MarketAnalysisPoller poller = new MarketAnalysisPoller(analysisRepo, targetRepo, pushClient);
        MarketAnalysisPoller.PollResult result = poller.pollOnce(DATE, SLOT);

        assertFalse(result.ok());
        assertEquals("no_analysis", result.skipReason());
        assertEquals(0, result.pushed());
        assertEquals(0, result.skippedByRateLimit());
        assertNull(result.analysisId());
        verify(pushClient, never()).push(any(PushMessageType.class), anyString(), anyString());
        verify(analysisRepo, never()).markPushed(anyLong());
    }

    @Test
    void pollOnceReturnsNoTargetsWhenRosterEmpty() {
        MarketAnalysisRepository analysisRepo = mock(MarketAnalysisRepository.class);
        BotTargetRepository targetRepo = mock(BotTargetRepository.class);
        LinePushClient pushClient = mock(LinePushClient.class);
        when(analysisRepo.findLatest(DATE, SLOT)).thenReturn(Optional.of(sampleAnalysis()));
        when(targetRepo.listActiveTargets()).thenReturn(List.of());
        when(pushClient.isPushEnabled()).thenReturn(true);

        MarketAnalysisPoller poller = new MarketAnalysisPoller(analysisRepo, targetRepo, pushClient);
        MarketAnalysisPoller.PollResult result = poller.pollOnce(DATE, SLOT);

        assertFalse(result.ok());
        assertEquals("no_targets", result.skipReason());
        assertEquals(42L, result.analysisId());
        assertEquals(0, result.skippedByRateLimit());
        verify(pushClient, never()).push(any(PushMessageType.class), anyString(), anyString());
        verify(analysisRepo, never()).markPushed(anyLong());
    }

    @Test
    void pollOnceSkipsGarbledSummaryWithoutResolvingTargets() {
        MarketAnalysisRepository analysisRepo = mock(MarketAnalysisRepository.class);
        BotTargetRepository targetRepo = mock(BotTargetRepository.class);
        LinePushClient pushClient = mock(LinePushClient.class);
        when(analysisRepo.findLatest(DATE, SLOT)).thenReturn(Optional.of(garbledAnalysis()));
        when(pushClient.isPushEnabled()).thenReturn(true);

        MarketAnalysisPoller poller = new MarketAnalysisPoller(analysisRepo, targetRepo, pushClient);
        MarketAnalysisPoller.PollResult result = poller.pollOnce(DATE, SLOT);

        assertFalse(result.ok());
        assertEquals("garbled_summary", result.skipReason());
        assertEquals(77L, result.analysisId());
        assertTrue(result.pushEnabled());
        verify(targetRepo, never()).listActiveTargets();
        verify(pushClient, never()).push(any(PushMessageType.class), anyString(), anyString());
        verify(analysisRepo, never()).markPushed(anyLong());
    }

    @Test
    void pollOnceSkipsPushWhenToggleOff() {
        MarketAnalysisRepository analysisRepo = mock(MarketAnalysisRepository.class);
        BotTargetRepository targetRepo = mock(BotTargetRepository.class);
        LinePushClient pushClient = mock(LinePushClient.class);
        when(analysisRepo.findLatest(DATE, SLOT)).thenReturn(Optional.of(sampleAnalysis()));
        when(targetRepo.listActiveTargets()).thenReturn(List.of(
                BotTarget.group("G1"), BotTarget.user("U1")));
        when(pushClient.isPushEnabled()).thenReturn(false);

        MarketAnalysisPoller poller = new MarketAnalysisPoller(analysisRepo, targetRepo, pushClient);
        MarketAnalysisPoller.PollResult result = poller.pollOnce(DATE, SLOT);

        assertTrue(result.ok());
        assertEquals(0, result.pushed());
        assertEquals(2, result.skippedByToggle());
        assertEquals(0, result.skippedByRateLimit());
        assertFalse(result.pushEnabled());
        verify(pushClient, never()).push(any(PushMessageType.class), anyString(), anyString());
        verify(analysisRepo, never()).markPushed(anyLong());
    }

    @Test
    void pollOncePushesEachTargetWhenToggleOn() {
        MarketAnalysisRepository analysisRepo = mock(MarketAnalysisRepository.class);
        BotTargetRepository targetRepo = mock(BotTargetRepository.class);
        LinePushClient pushClient = mock(LinePushClient.class);
        when(analysisRepo.findLatest(DATE, SLOT)).thenReturn(Optional.of(sampleAnalysis()));
        when(targetRepo.listActiveTargets()).thenReturn(List.of(
                BotTarget.group("G1"),
                BotTarget.user("U1"),
                BotTarget.user("U2")));
        when(pushClient.isPushEnabled()).thenReturn(true);
        when(pushClient.push(eq(PushMessageType.PUBLIC_ANALYSIS), anyString(), anyString()))
                .thenReturn(LinePushClient.PushAttempt.sent());

        MarketAnalysisPoller poller = new MarketAnalysisPoller(analysisRepo, targetRepo, pushClient);
        MarketAnalysisPoller.PollResult result = poller.pollOnce(DATE, SLOT);

        assertTrue(result.ok());
        assertEquals(3, result.pushed());
        assertEquals(0, result.skippedByToggle());
        assertEquals(0, result.skippedByRateLimit());
        assertTrue(result.pushEnabled());
        verify(pushClient, times(3)).push(eq(PushMessageType.PUBLIC_ANALYSIS), anyString(), anyString());
        verify(pushClient).push(eq(PushMessageType.PUBLIC_ANALYSIS), eq("G1"), anyString());
        verify(pushClient).push(eq(PushMessageType.PUBLIC_ANALYSIS), eq("U1"), anyString());
        verify(pushClient).push(eq(PushMessageType.PUBLIC_ANALYSIS), eq("U2"), anyString());
        verify(analysisRepo).markPushed(42L);
    }

    @Test
    void pollOnceMessageHeaderOnlyContainsAnalysisDate() {
        MarketAnalysisRepository analysisRepo = mock(MarketAnalysisRepository.class);
        BotTargetRepository targetRepo = mock(BotTargetRepository.class);
        LinePushClient pushClient = mock(LinePushClient.class);
        when(analysisRepo.findLatest(DATE, SLOT)).thenReturn(Optional.of(sampleAnalysis()));
        when(targetRepo.listActiveTargets()).thenReturn(List.of(BotTarget.user("U1")));
        when(pushClient.isPushEnabled()).thenReturn(true);
        when(pushClient.push(eq(PushMessageType.PUBLIC_ANALYSIS), eq("U1"), anyString()))
                .thenReturn(LinePushClient.PushAttempt.sent());

        MarketAnalysisPoller poller = new MarketAnalysisPoller(analysisRepo, targetRepo, pushClient);
        poller.pollOnce(DATE, SLOT);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(pushClient).push(eq(PushMessageType.PUBLIC_ANALYSIS), eq("U1"), message.capture());
        assertEquals(DATE + "\nLatest pre-open summary", message.getValue());
    }

    @Test
    void pollOnceUsesShortExcerptAndDetailLinkWhenPublicAnalysisBaseUrlIsConfigured() {
        MarketAnalysisRepository analysisRepo = mock(MarketAnalysisRepository.class);
        BotTargetRepository targetRepo = mock(BotTargetRepository.class);
        LinePushClient pushClient = mock(LinePushClient.class);
        String firstParagraph = "本週重點：市場情緒偏多但波動仍高\n半導體權值帶動盤面延續上攻，資金集中在大型科技與 AI 供應鏈"
                + "A".repeat(120);
        MarketAnalysis analysis = new MarketAnalysis(
                42L, DATE, SLOT, "07:30",
                "gpt-5", "v2",
                firstParagraph + "\n\n第二段不應出現在 LINE 摘要",
                "{\"k\":\"v\"}",
                Instant.parse("2026-04-20T07:45:00Z"));
        when(analysisRepo.findLatest(DATE, SLOT)).thenReturn(Optional.of(analysis));
        when(targetRepo.listActiveTargets()).thenReturn(List.of(BotTarget.user("U1")));
        when(pushClient.isPushEnabled()).thenReturn(true);
        when(pushClient.push(eq(PushMessageType.PUBLIC_ANALYSIS), eq("U1"), anyString()))
                .thenReturn(LinePushClient.PushAttempt.sent());

        MarketAnalysisPoller poller = new MarketAnalysisPoller(
                analysisRepo, targetRepo, pushClient, "https://example.test/analyses/");
        poller.pollOnce(DATE, SLOT);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(pushClient).push(eq(PushMessageType.PUBLIC_ANALYSIS), eq("U1"), message.capture());
        String expectedExcerpt = firstParagraph.replaceAll("\\s+", " ")
                .substring(0, 100)
                .stripTrailing() + "...";
        assertEquals("2026-04-20 市場分析\n"
                + expectedExcerpt + "\n\n"
                + "看完整分析：\n"
                + "https://example.test/analyses/42", message.getValue());
    }

    @Test
    void pollOnceCountsPartialFailures() {
        MarketAnalysisRepository analysisRepo = mock(MarketAnalysisRepository.class);
        BotTargetRepository targetRepo = mock(BotTargetRepository.class);
        LinePushClient pushClient = mock(LinePushClient.class);
        when(analysisRepo.findLatest(DATE, SLOT)).thenReturn(Optional.of(sampleAnalysis()));
        when(targetRepo.listActiveTargets()).thenReturn(List.of(
                BotTarget.group("G1"), BotTarget.user("U1")));
        when(pushClient.isPushEnabled()).thenReturn(true);
        when(pushClient.push(eq(PushMessageType.PUBLIC_ANALYSIS), eq("U1"), anyString()))
                .thenReturn(LinePushClient.PushAttempt.sent());
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(pushClient).push(eq(PushMessageType.PUBLIC_ANALYSIS), eq("G1"), anyString());

        MarketAnalysisPoller poller = new MarketAnalysisPoller(analysisRepo, targetRepo, pushClient);
        MarketAnalysisPoller.PollResult result = poller.pollOnce(DATE, SLOT);

        assertTrue(result.ok());
        assertEquals(1, result.pushed());
        assertEquals(0, result.skippedByRateLimit());
        verify(analysisRepo).markPushed(42L);
    }

    @Test
    void pollOnceTracksRateLimitedTargetsWithoutMarkingPushed() {
        MarketAnalysisRepository analysisRepo = mock(MarketAnalysisRepository.class);
        BotTargetRepository targetRepo = mock(BotTargetRepository.class);
        LinePushClient pushClient = mock(LinePushClient.class);
        when(analysisRepo.findLatest(DATE, SLOT)).thenReturn(Optional.of(sampleAnalysis()));
        when(targetRepo.listActiveTargets()).thenReturn(List.of(BotTarget.user("U1"), BotTarget.user("U2")));
        when(pushClient.isPushEnabled()).thenReturn(true);
        when(pushClient.push(eq(PushMessageType.PUBLIC_ANALYSIS), eq("U1"), anyString()))
                .thenReturn(LinePushClient.PushAttempt.rateLimitSkipped());
        when(pushClient.push(eq(PushMessageType.PUBLIC_ANALYSIS), eq("U2"), anyString()))
                .thenReturn(LinePushClient.PushAttempt.rateLimitSkipped());

        MarketAnalysisPoller poller = new MarketAnalysisPoller(analysisRepo, targetRepo, pushClient);
        MarketAnalysisPoller.PollResult result = poller.pollOnce(DATE, SLOT);

        assertTrue(result.ok());
        assertEquals(0, result.pushed());
        assertEquals(2, result.skippedByRateLimit());
        verify(analysisRepo, never()).markPushed(anyLong());
    }

    @Test
    void pollOnceDefaultsSlotWhenBlank() {
        MarketAnalysisRepository analysisRepo = mock(MarketAnalysisRepository.class);
        BotTargetRepository targetRepo = mock(BotTargetRepository.class);
        LinePushClient pushClient = mock(LinePushClient.class);
        when(analysisRepo.findLatest(eq(DATE), eq(MarketAnalysisPoller.DEFAULT_SLOT)))
                .thenReturn(Optional.empty());

        MarketAnalysisPoller poller = new MarketAnalysisPoller(analysisRepo, targetRepo, pushClient);
        MarketAnalysisPoller.PollResult result = poller.pollOnce(DATE, "");

        assertEquals(MarketAnalysisPoller.DEFAULT_SLOT, result.analysisSlot());
        verify(analysisRepo).findLatest(DATE, MarketAnalysisPoller.DEFAULT_SLOT);
    }

    @Test
    void pushLatestToTestAccountsNowUsesLatestAnyAndBypassesPushToggle() {
        MarketAnalysisRepository analysisRepo = mock(MarketAnalysisRepository.class);
        BotTargetRepository targetRepo = mock(BotTargetRepository.class);
        LinePushClient pushClient = mock(LinePushClient.class);
        when(analysisRepo.findLatestAny()).thenReturn(Optional.of(sampleAnalysis()));
        when(targetRepo.listActiveTestUserIds()).thenReturn(List.of("U_TEST"));
        when(pushClient.pushIgnoringToggle(eq(PushMessageType.PUBLIC_ANALYSIS), anyString(), anyString()))
                .thenReturn(LinePushClient.PushAttempt.sent());

        MarketAnalysisPoller poller = new MarketAnalysisPoller(analysisRepo, targetRepo, pushClient);
        MarketAnalysisPoller.PollResult result = poller.pushLatestToTestAccountsNow();

        assertTrue(result.ok());
        assertEquals(1, result.pushed());
        assertEquals(0, result.skippedByRateLimit());
        assertEquals(42L, result.analysisId());
        verify(pushClient).pushIgnoringToggle(eq(PushMessageType.PUBLIC_ANALYSIS), eq("U_TEST"), anyString());
        verify(pushClient, never()).push(any(PushMessageType.class), anyString(), anyString());
    }

    @Test
    void pushLatestToTestAccountsNowSkipsGarbledSummary() {
        MarketAnalysisRepository analysisRepo = mock(MarketAnalysisRepository.class);
        BotTargetRepository targetRepo = mock(BotTargetRepository.class);
        LinePushClient pushClient = mock(LinePushClient.class);
        when(analysisRepo.findLatestAny()).thenReturn(Optional.of(garbledAnalysis()));

        MarketAnalysisPoller poller = new MarketAnalysisPoller(analysisRepo, targetRepo, pushClient);
        MarketAnalysisPoller.PollResult result = poller.pushLatestToTestAccountsNow();

        assertFalse(result.ok());
        assertEquals("garbled_summary", result.skipReason());
        assertEquals(77L, result.analysisId());
        verify(targetRepo, never()).listActiveTestUserIds();
        verify(pushClient, never()).pushIgnoringToggle(any(PushMessageType.class), anyString(), anyString());
        verify(pushClient, never()).push(any(PushMessageType.class), anyString(), anyString());
    }

    @Test
    void pushLatestToTestAccountsNowReturnsNoTestTargetsWhenEmpty() {
        MarketAnalysisRepository analysisRepo = mock(MarketAnalysisRepository.class);
        BotTargetRepository targetRepo = mock(BotTargetRepository.class);
        LinePushClient pushClient = mock(LinePushClient.class);
        when(analysisRepo.findLatestAny()).thenReturn(Optional.of(sampleAnalysis()));
        when(targetRepo.listActiveTestUserIds()).thenReturn(List.of());

        MarketAnalysisPoller poller = new MarketAnalysisPoller(analysisRepo, targetRepo, pushClient);
        MarketAnalysisPoller.PollResult result = poller.pushLatestToTestAccountsNow();

        assertFalse(result.ok());
        assertEquals("no_test_targets", result.skipReason());
        assertEquals(0, result.pushed());
        verify(pushClient, never()).pushIgnoringToggle(any(PushMessageType.class), anyString(), anyString());
    }

    @Test
    void pushLatestToTestAccountsNowTracksRateLimitedUsers() {
        MarketAnalysisRepository analysisRepo = mock(MarketAnalysisRepository.class);
        BotTargetRepository targetRepo = mock(BotTargetRepository.class);
        LinePushClient pushClient = mock(LinePushClient.class);
        when(analysisRepo.findLatestAny()).thenReturn(Optional.of(sampleAnalysis()));
        when(targetRepo.listActiveTestUserIds()).thenReturn(List.of("U_TEST"));
        when(pushClient.pushIgnoringToggle(eq(PushMessageType.PUBLIC_ANALYSIS), anyString(), anyString()))
                .thenReturn(LinePushClient.PushAttempt.rateLimitSkipped());

        MarketAnalysisPoller poller = new MarketAnalysisPoller(analysisRepo, targetRepo, pushClient);
        MarketAnalysisPoller.PollResult result = poller.pushLatestToTestAccountsNow();

        assertTrue(result.ok());
        assertEquals(0, result.pushed());
        assertEquals(1, result.skippedByRateLimit());
    }

    @Test
    void pollUnpushedOnceReturnsNoPendingAnalysisWhenMissing() {
        MarketAnalysisRepository analysisRepo = mock(MarketAnalysisRepository.class);
        BotTargetRepository targetRepo = mock(BotTargetRepository.class);
        LinePushClient pushClient = mock(LinePushClient.class);
        when(analysisRepo.findLatestUnpushed(DATE, SLOT)).thenReturn(Optional.empty());
        when(pushClient.isPushEnabled()).thenReturn(true);

        MarketAnalysisPoller poller = new MarketAnalysisPoller(analysisRepo, targetRepo, pushClient);
        MarketAnalysisPoller.PollResult result = poller.pollUnpushedOnce(DATE, SLOT);

        assertFalse(result.ok());
        assertEquals("no_pending_analysis", result.skipReason());
        assertEquals(0, result.skippedByRateLimit());
        verify(pushClient, never()).push(any(PushMessageType.class), anyString(), anyString());
        verify(analysisRepo, never()).markPushed(anyLong());
    }

    @Test
    void pollUnpushedOnceMarksPushedAfterSuccessfulCatchUp() {
        MarketAnalysisRepository analysisRepo = mock(MarketAnalysisRepository.class);
        BotTargetRepository targetRepo = mock(BotTargetRepository.class);
        LinePushClient pushClient = mock(LinePushClient.class);
        when(analysisRepo.findLatestUnpushed(DATE, WEEKLY_SLOT))
                .thenReturn(Optional.of(new MarketAnalysis(
                        99L, DATE, WEEKLY_SLOT, "manual retry",
                        "gpt-5", "v1", "Weekly summary", "{\"dimension\":\"weekly\"}",
                        Instant.parse("2026-04-20T08:00:00Z"))));
        when(targetRepo.listActiveTargets()).thenReturn(List.of(BotTarget.user("U1")));
        when(pushClient.isPushEnabled()).thenReturn(true);
        when(pushClient.push(eq(PushMessageType.PUBLIC_ANALYSIS), eq("U1"), anyString()))
                .thenReturn(LinePushClient.PushAttempt.sent());

        MarketAnalysisPoller poller = new MarketAnalysisPoller(analysisRepo, targetRepo, pushClient);
        MarketAnalysisPoller.PollResult result = poller.pollUnpushedOnce(DATE, WEEKLY_SLOT);

        assertTrue(result.ok());
        assertEquals(1, result.pushed());
        assertEquals(0, result.skippedByRateLimit());
        verify(pushClient).push(eq(PushMessageType.PUBLIC_ANALYSIS), eq("U1"), anyString());
        verify(analysisRepo).markPushed(99L);
    }

    @Test
    void disableStaleUnpushedBeforeDelegatesToRepository() {
        MarketAnalysisRepository analysisRepo = mock(MarketAnalysisRepository.class);
        BotTargetRepository targetRepo = mock(BotTargetRepository.class);
        LinePushClient pushClient = mock(LinePushClient.class);
        when(analysisRepo.disableUnpushedBefore("2026-04-21")).thenReturn(2);

        MarketAnalysisPoller poller = new MarketAnalysisPoller(analysisRepo, targetRepo, pushClient);
        MarketAnalysisPoller.CleanupResult result = poller.disableStaleUnpushedBefore("2026-04-21");

        assertEquals("2026-04-21", result.beforeAnalysisDate());
        assertEquals(2, result.disabled());
        verify(analysisRepo).disableUnpushedBefore("2026-04-21");
    }
}
