package com.zack.linerelay.stock;

import com.zack.linerelay.push.LinePushClient;
import com.zack.linerelay.push.PushMessageType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for LINE stock-query command handling.
 */
class StockQueryServiceTest {

    @Test
    void ignoresNonStockMessages() {
        StockSignalClient signalClient = Mockito.mock(StockSignalClient.class);
        LinePushClient pushClient = Mockito.mock(LinePushClient.class);
        StockQueryService service = new StockQueryService(signalClient, new NoopStockSignalCache(), pushClient);

        assertFalse(service.handle("U1", "hello"));
        verifyNoInteractions(signalClient, pushClient);
    }

    @Test
    void pushesRealtimeGeneratedSignalAndCachesReply() {
        StockSignalClient signalClient = Mockito.mock(StockSignalClient.class);
        StockSignalCache cache = Mockito.mock(StockSignalCache.class);
        LinePushClient pushClient = Mockito.mock(LinePushClient.class);
        when(cache.get("2330")).thenReturn(Optional.empty());
        when(signalClient.generate("2330")).thenReturn(sampleResponse());
        when(pushClient.push(eq(PushMessageType.STOCK_QUERY), eq("U1"), Mockito.anyString()))
                .thenReturn(LinePushClient.PushAttempt.sent());
        StockQueryService service = new StockQueryService(signalClient, cache, pushClient);

        assertTrue(service.handle("U1", "股票 2330.TW"));

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(signalClient).generate("2330");
        verify(cache).put(eq("2330"), Mockito.anyString());
        verify(pushClient).push(eq(PushMessageType.STOCK_QUERY), eq("U1"), message.capture());
        assertTrue(message.getValue().contains("2330"));
        assertTrue(message.getValue().contains("AI demand supports the setup"));
    }

    @Test
    void pushesCachedSignalWithoutCallingModel() {
        StockSignalClient signalClient = Mockito.mock(StockSignalClient.class);
        StockSignalCache cache = Mockito.mock(StockSignalCache.class);
        LinePushClient pushClient = Mockito.mock(LinePushClient.class);
        when(cache.get("2330")).thenReturn(Optional.of("cached stock reply"));
        when(pushClient.push(eq(PushMessageType.STOCK_QUERY), eq("U1"), Mockito.anyString()))
                .thenReturn(LinePushClient.PushAttempt.sent());
        StockQueryService service = new StockQueryService(signalClient, cache, pushClient);

        assertTrue(service.handle("U1", "股票 2330"));

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verifyNoInteractions(signalClient);
        verify(pushClient).push(eq(PushMessageType.STOCK_QUERY), eq("U1"), message.capture());
        assertEquals("cached stock reply", message.getValue());
    }

    @Test
    void pushesUnavailableMessageWhenGenerationFails() {
        StockSignalClient signalClient = Mockito.mock(StockSignalClient.class);
        LinePushClient pushClient = Mockito.mock(LinePushClient.class);
        when(signalClient.generate("NVDA")).thenThrow(new StockSignalUnavailableException("disabled"));
        when(pushClient.push(eq(PushMessageType.STOCK_QUERY), eq("U1"), Mockito.anyString()))
                .thenReturn(LinePushClient.PushAttempt.sent());
        StockQueryService service = new StockQueryService(signalClient, new NoopStockSignalCache(), pushClient);

        assertTrue(service.handle("U1", "查股 NVDA"));

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(pushClient).push(eq(PushMessageType.STOCK_QUERY), eq("U1"), message.capture());
        assertTrue(message.getValue().contains("NVDA"));
    }

    @Test
    void pushesUsageWhenCommandHasNoTicker() {
        StockSignalClient signalClient = Mockito.mock(StockSignalClient.class);
        LinePushClient pushClient = Mockito.mock(LinePushClient.class);
        when(pushClient.push(eq(PushMessageType.STOCK_QUERY), eq("U1"), Mockito.anyString()))
                .thenReturn(LinePushClient.PushAttempt.sent());
        StockQueryService service = new StockQueryService(signalClient, new NoopStockSignalCache(), pushClient);

        assertTrue(service.handle("U1", "查股"));

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verifyNoInteractions(signalClient);
        verify(pushClient).push(eq(PushMessageType.STOCK_QUERY), eq("U1"), message.capture());
        assertTrue(message.getValue().contains("2330"));
        assertTrue(message.getValue().contains("NVDA"));
    }

    @Test
    void stockAnalysisRoutePassesFreeFormQueryUnchangedAndSkipsCache() {
        StockSignalClient signalClient = Mockito.mock(StockSignalClient.class);
        StockSignalCache cache = Mockito.mock(StockSignalCache.class);
        LinePushClient pushClient = Mockito.mock(LinePushClient.class);
        when(signalClient.generate("Rocket Lab (RKLB)")).thenReturn(sampleResponse());
        when(pushClient.push(eq(PushMessageType.STOCK_QUERY), eq("U1"), Mockito.anyString()))
                .thenReturn(LinePushClient.PushAttempt.sent());
        StockQueryService service = new StockQueryService(signalClient, cache, pushClient);

        assertTrue(service.handle("U1", "股價分析 Rocket Lab (RKLB)"));

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(signalClient).generate("Rocket Lab (RKLB)");
        verifyNoInteractions(cache);
        verify(pushClient).push(eq(PushMessageType.STOCK_QUERY), eq("U1"), message.capture());
        assertTrue(message.getValue().contains("AI demand supports the setup"));
    }

    @Test
    void stockAnalysisRouteAcceptsFullWidthSpaceAndTrimsTrailingWhitespace() {
        StockSignalClient signalClient = Mockito.mock(StockSignalClient.class);
        LinePushClient pushClient = Mockito.mock(LinePushClient.class);
        when(signalClient.generate("RKLB")).thenReturn(sampleResponse());
        when(pushClient.push(eq(PushMessageType.STOCK_QUERY), eq("U1"), Mockito.anyString()))
                .thenReturn(LinePushClient.PushAttempt.sent());
        StockQueryService service = new StockQueryService(signalClient, new NoopStockSignalCache(), pushClient);

        assertTrue(service.handle("U1", "股價分析　RKLB　"));

        verify(signalClient).generate("RKLB");
    }

    @Test
    void stockAnalysisRouteRejectsMissingSeparator() {
        StockSignalClient signalClient = Mockito.mock(StockSignalClient.class);
        LinePushClient pushClient = Mockito.mock(LinePushClient.class);
        StockQueryService service = new StockQueryService(signalClient, new NoopStockSignalCache(), pushClient);

        assertFalse(service.handle("U1", "股價分析RKLB"));

        verifyNoInteractions(signalClient, pushClient);
    }

    @Test
    void stockAnalysisRouteWithoutContentRepliesUsage() {
        StockSignalClient signalClient = Mockito.mock(StockSignalClient.class);
        LinePushClient pushClient = Mockito.mock(LinePushClient.class);
        when(pushClient.push(eq(PushMessageType.STOCK_QUERY), eq("U1"), Mockito.anyString()))
                .thenReturn(LinePushClient.PushAttempt.sent());
        StockQueryService service = new StockQueryService(signalClient, new NoopStockSignalCache(), pushClient);

        assertTrue(service.handle("U1", "股價分析"));
        assertTrue(service.handle("U1", "股價分析 "));

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verifyNoInteractions(signalClient);
        verify(pushClient, Mockito.times(2))
                .push(eq(PushMessageType.STOCK_QUERY), eq("U1"), message.capture());
        for (String reply : message.getAllValues()) {
            assertTrue(reply.contains("股價分析"));
        }
    }

    private GeneratedStockSignalResponse sampleResponse() {
        return new GeneratedStockSignalResponse(
                "2330",
                "TSMC",
                "TW",
                "long",
                "swing",
                "medium",
                "Wait for a pullback near support.",
                "Take profit in stages.",
                "Exit if the thesis fails.",
                "SOX weakness is the main risk.",
                "AI demand supports the setup.",
                "ADR and AI demand are constructive.",
                "Research only, not investment advice.",
                null,
                "line",
                "gpt-5.4-mini",
                "stock-signal-realtime-v1",
                "signal_and_analysis_context",
                Instant.parse("2026-05-06T02:00:00Z")
        );
    }
}
