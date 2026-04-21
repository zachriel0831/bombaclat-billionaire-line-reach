package com.zack.linerelay.market;

import com.zack.linerelay.db.BotTarget;
import com.zack.linerelay.db.BotTargetRepository;
import com.zack.linerelay.db.MarketAnalysis;
import com.zack.linerelay.db.MarketAnalysisRepository;
import com.zack.linerelay.push.LinePushClient;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketAnalysisPollerTest {

    private static final String DATE = "2026-04-20";
    private static final String SLOT = "pre_tw_open";

    private MarketAnalysis sampleAnalysis() {
        return new MarketAnalysis(
                42L, DATE, SLOT, "07:30",
                "gpt-5", "v2",
                "Latest pre-open summary",
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
        assertNull(result.analysisId());
        verify(pushClient, never()).push(anyString(), anyString());
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
        verify(pushClient, never()).push(anyString(), anyString());
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
        assertFalse(result.pushEnabled());
        verify(pushClient, never()).push(anyString(), anyString());
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

        MarketAnalysisPoller poller = new MarketAnalysisPoller(analysisRepo, targetRepo, pushClient);
        MarketAnalysisPoller.PollResult result = poller.pollOnce(DATE, SLOT);

        assertTrue(result.ok());
        assertEquals(3, result.pushed());
        assertEquals(0, result.skippedByToggle());
        assertTrue(result.pushEnabled());
        verify(pushClient, times(3)).push(anyString(), anyString());
        verify(pushClient).push(eq("G1"), anyString());
        verify(pushClient).push(eq("U1"), anyString());
        verify(pushClient).push(eq("U2"), anyString());
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
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(pushClient).push(eq("G1"), any());

        MarketAnalysisPoller poller = new MarketAnalysisPoller(analysisRepo, targetRepo, pushClient);
        MarketAnalysisPoller.PollResult result = poller.pollOnce(DATE, SLOT);

        assertTrue(result.ok());
        assertEquals(1, result.pushed());
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
}
