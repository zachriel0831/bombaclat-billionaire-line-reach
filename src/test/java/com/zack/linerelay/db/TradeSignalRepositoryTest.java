package com.zack.linerelay.db;

import com.zack.linerelay.config.LineProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H2-backed tests for reading upstream stock signals.
 */
@JdbcTest
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@Sql(scripts = {"/test-schema.sql", "/test-data.sql"})
class TradeSignalRepositoryTest {

    @Autowired
    private JdbcTemplate jdbc;

    private TradeSignalRepository repo() {
        LineProperties props = new LineProperties(
                "s", "t", null,
                new LineProperties.Push(false, true),
                new LineProperties.Mysql(true, "t_market_analyses", "t_bot_group_info", "t_bot_user_info",
                        "t_trade_signals"));
        return new TradeSignalRepository(jdbc, props);
    }

    @Test
    void findLatestActiveByTickerReturnsNewestNonSupersededSignalWithAnalysisFields() {
        insertAnalysis(42);
        insertSignal("old", "hash000000000000000000000000000000000001", "2026-04-28",
                "2330", "old rationale", "pending_review", "2026-04-28 07:30:00");
        insertSignal("new", "hash000000000000000000000000000000000002", "2026-04-29",
                "2330", "new rationale", "pending_review", "2026-04-29 07:30:00");
        insertSignal("superseded", "hash000000000000000000000000000000000003", "2026-04-30",
                "2330", "ignored rationale", "superseded", "2026-04-30 07:30:00");

        Optional<TradeSignal> found = repo().findLatestActiveByTicker("2330.TW");

        assertTrue(found.isPresent());
        assertEquals("2026-04-29", found.get().analysisDate());
        assertEquals("new rationale", found.get().rationale());
        assertEquals("gpt-5", found.get().analysisModel());
        assertEquals("market-analysis-v1", found.get().analysisPromptVersion());
        assertEquals("{\"headline\":\"台股偏多\"}", found.get().analysisStructuredJson());
    }

    @Test
    void findLatestActiveByTickerReturnsEmptyForUnknownTicker() {
        assertTrue(repo().findLatestActiveByTicker("9999").isEmpty());
    }

    private void insertAnalysis(long id) {
        jdbc.update("""
                INSERT INTO t_market_analyses
                  (id, analysis_date, analysis_slot, scheduled_time_local, model, prompt_version,
                   summary_text, raw_json, structured_json, updated_at)
                VALUES (?, '2026-04-29', 'pre_tw_open', '07:30', 'gpt-5', 'market-analysis-v1',
                        '台股偏多，半導體領漲。', '{}', '{"headline":"台股偏多"}', ?)
                """,
                id,
                java.sql.Timestamp.valueOf("2026-04-29 07:31:00"));
    }

    private void insertSignal(
            String key,
            String hash,
            String analysisDate,
            String ticker,
            String rationale,
            String status,
            String updatedAt
    ) {
        jdbc.update("""
                INSERT INTO t_trade_signals
                  (signal_key, idempotency_key, analysis_id, analysis_date, analysis_slot,
                   market, ticker, name, signal_type, strategy_type, direction, confidence,
                   entry_zone, invalidation, take_profit_zone, holding_horizon, rationale,
                   risk_notes, source_event_ids, status, raw_json, updated_at)
                VALUES (?, ?, 42, ?, 'pre_tw_open',
                        'TW', ?, '台積電', 'analysis_stock_watch', 'swing', 'long', 'medium',
                        '{"low":600,"high":610}', '{"price":590}', '{"first":630}',
                        'swing', ?, '["美股回落"]', '[101]', ?, '{"source":"test"}', ?)
                """,
                key, hash, analysisDate, ticker, rationale, status,
                java.sql.Timestamp.valueOf(updatedAt));
    }
}
