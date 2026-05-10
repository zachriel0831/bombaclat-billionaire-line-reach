package com.zack.linerelay.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zack.linerelay.config.LineProperties;
import com.zack.linerelay.db.BotTargetRepository;
import com.zack.linerelay.market.MarketAnalysisPoller;
import com.zack.linerelay.push.PushModeService;
import com.zack.linerelay.stock.StockQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for LINE event semantics, database state merging, and runtime text
 * commands handled by the processor.
 */
class WebhookEventProcessorTest {

    private static final String USER_A = "U_A";
    private static final String USER_B = "U_B";
    private static final String GROUP_A = "G_A";

    private ObjectMapper mapper;
    private BotTargetRepository repo;
    private MarketAnalysisPoller poller;
    private PushModeService pushModeService;
    private StockQueryService stockQueryService;
    private WebhookEventProcessor processor;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        repo = Mockito.mock(BotTargetRepository.class);
        poller = Mockito.mock(MarketAnalysisPoller.class);
        stockQueryService = Mockito.mock(StockQueryService.class);
        pushModeService = new PushModeService(new LineProperties(
                "s", "t", null, new LineProperties.Push(true, false), null));
        processor = new WebhookEventProcessor(
                providerOf(repo), providerOf(poller), pushModeService, providerOf(stockQueryService));
    }

    private <T> ObjectProvider<T> providerOf(T instance) {
        // Production uses ObjectProvider because DB-backed beans may be disabled
        // by configuration; tests need the same optional-bean behavior.
        @SuppressWarnings("unchecked")
        ObjectProvider<T> p = Mockito.mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(instance);
        return p;
    }

    private JsonNode parseEvents(String json) throws Exception {
        return mapper.readTree(json).path("events");
    }

    @Test
    void followEventMarksUserActive() throws Exception {
        JsonNode events = parseEvents("""
                {"events":[{"type":"follow","source":{"type":"user","userId":"U_A"}}]}
                """);

        WebhookEventProcessor.Summary summary = processor.process(events);

        verify(repo).upsertUser(USER_A, true);
        verify(repo, never()).upsertGroup(Mockito.anyString(), Mockito.anyBoolean());
        assertEquals(1, summary.events());
        assertEquals(1, summary.activeUsers());
        assertEquals(0, summary.inactiveUsers());
    }

    @Test
    void unfollowEventMarksUserInactive() throws Exception {
        JsonNode events = parseEvents("""
                {"events":[{"type":"unfollow","source":{"type":"user","userId":"U_A"}}]}
                """);

        processor.process(events);

        verify(repo).upsertUser(USER_A, false);
    }

    @Test
    void joinEventMarksGroupActive() throws Exception {
        JsonNode events = parseEvents("""
                {"events":[{"type":"join","source":{"type":"group","groupId":"G_A"}}]}
                """);

        processor.process(events);

        verify(repo).upsertGroup(GROUP_A, true);
    }

    @Test
    void leaveEventMarksGroupInactive() throws Exception {
        JsonNode events = parseEvents("""
                {"events":[{"type":"leave","source":{"type":"group","groupId":"G_A"}}]}
                """);

        processor.process(events);

        verify(repo).upsertGroup(GROUP_A, false);
    }

    @Test
    void memberJoinedRegistersGroupAndJoinedUsers() throws Exception {
        JsonNode events = parseEvents("""
                {"events":[{"type":"memberJoined",
                            "source":{"type":"group","groupId":"G_A","userId":"U_A"},
                            "joined":{"members":[{"type":"user","userId":"U_B"}]}}]}
                """);

        processor.process(events);

        verify(repo).upsertGroup(GROUP_A, true);
        verify(repo).upsertUser(USER_A, true);
        verify(repo).upsertUser(USER_B, true);
    }

    @Test
    void roomJoinFallsBackToRoomId() throws Exception {
        JsonNode events = parseEvents("""
                {"events":[{"type":"join","source":{"type":"room","roomId":"R_A"}}]}
                """);

        processor.process(events);

        verify(repo).upsertGroup("R_A", true);
    }

    @Test
    void followThenUnfollowInSameBatchEndsInactive() throws Exception {
        JsonNode events = parseEvents("""
                {"events":[
                  {"type":"follow","source":{"type":"user","userId":"U_A"}},
                  {"type":"unfollow","source":{"type":"user","userId":"U_A"}}
                ]}
                """);

        processor.process(events);

        verify(repo, times(1)).upsertUser(USER_A, false);
        verify(repo, never()).upsertUser(eq(USER_A), eq(true));
    }

    @Test
    void messageEventKeepsDefaultActiveState() throws Exception {
        JsonNode events = parseEvents("""
                {"events":[{"type":"message","source":{"type":"user","userId":"U_A"},
                            "message":{"type":"text","text":"hi"}}]}
                """);

        WebhookEventProcessor.Summary summary = processor.process(events);

        verify(repo).upsertUser(USER_A, true);
        assertEquals(1, summary.activeUsers());
    }

    @Test
    void ignoresEventsWithoutSource() throws Exception {
        JsonNode events = parseEvents("""
                {"events":[{"type":"follow"}, {"type":"unfollow","source":null}]}
                """);

        processor.process(events);

        verifyNoInteractions(repo);
    }

    @Test
    void emptyOrMissingEventsIsNoOp() throws Exception {
        processor.process(parseEvents("{\"events\":[]}"));
        processor.process(parseEvents("{}"));

        verifyNoInteractions(repo);
    }

    @Test
    void worksWithoutRepositoryWhenMysqlDisabled() throws Exception {
        WebhookEventProcessor noRepoProcessor = new WebhookEventProcessor(
                providerOf(null), providerOf(poller), pushModeService, providerOf(stockQueryService));

        JsonNode events = parseEvents("""
                {"events":[{"type":"follow","source":{"type":"user","userId":"U_A"}}]}
                """);

        WebhookEventProcessor.Summary summary = noRepoProcessor.process(events);

        assertEquals(1, summary.events());
        assertEquals(1, summary.users());
        assertEquals(0, summary.activeUsers(),
                "without repository, active counts stay 0 (no writes attempted)");
    }

    @Test
    void commandSwitchesToTestModeWhenMessageStartsWithKeyword() throws Exception {
        PushModeService modes = new PushModeService(new LineProperties(
                "s", "t", null, new LineProperties.Push(false, false), null));
        WebhookEventProcessor commandProcessor = new WebhookEventProcessor(
                providerOf(repo), providerOf(poller), modes, providerOf(stockQueryService));
        JsonNode events = parseEvents("""
                {"events":[{"type":"message","source":{"type":"user","userId":"U_A"},
                            "message":{"type":"text","text":"測試西卡卡 現在切測試"}}]}
                """);

        WebhookEventProcessor.Summary summary = commandProcessor.process(events);

        assertEquals(1, summary.commands());
        assertEquals(true, modes.isPushEnabled());
        assertEquals(true, modes.isPushTestOnly());
    }

    @Test
    void commandDisablesPushWhenMessageStartsWithKeyword() throws Exception {
        JsonNode events = parseEvents("""
                {"events":[{"type":"message","source":{"type":"user","userId":"U_A"},
                            "message":{"type":"text","text":"關閉西卡卡"}}]}
                """);

        WebhookEventProcessor.Summary summary = processor.process(events);

        assertEquals(1, summary.commands());
        assertEquals(false, pushModeService.isPushEnabled());
        assertEquals(false, pushModeService.isPushTestOnly());
    }

    @Test
    void commandPushesLatestAnalysisToTestAccountsWhenMessageStartsWithKeyword() throws Exception {
        JsonNode events = parseEvents("""
                {"events":[{"type":"message","source":{"type":"user","userId":"U_A"},
                            "message":{"type":"text","text":"西卡卡推送 立刻測試"}}]}
                """);

        WebhookEventProcessor.Summary summary = processor.process(events);

        assertEquals(1, summary.commands());
        verify(poller).pushLatestToTestAccountsNow();
    }

    @Test
    void stockCommandUsesGroupAsReplyTargetWhenMessageComesFromGroup() throws Exception {
        when(stockQueryService.handle(eq(GROUP_A), eq("股票 2330"))).thenReturn(true);
        JsonNode events = parseEvents("""
                {"events":[{"type":"message","source":{"type":"group","groupId":"G_A","userId":"U_A"},
                            "message":{"type":"text","text":"股票 2330"}}]}
                """);

        WebhookEventProcessor.Summary summary = processor.process(events);

        assertEquals(1, summary.commands());
        verify(stockQueryService).handle(eq(GROUP_A), eq("股票 2330"));
    }
}
