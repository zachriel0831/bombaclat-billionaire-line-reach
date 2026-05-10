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
 * H2-backed tests for latest-row selection from `t_market_analyses`.
 */
@JdbcTest
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@Sql(scripts = {"/test-schema.sql", "/test-data.sql"})
class MarketAnalysisRepositoryTest {

    @Autowired
    private JdbcTemplate jdbc;

    private MarketAnalysisRepository repo() {
        LineProperties props = new LineProperties(
                "s", "t", null,
                new LineProperties.Push(false, true),
                new LineProperties.Mysql(true, "t_market_analyses", "t_bot_group_info", "t_bot_user_info",
                        "t_trade_signals"));
        return new MarketAnalysisRepository(jdbc, props);
    }

    @Test
    void findLatestReturnsMostRecentlyUpdatedRow() {
        Optional<MarketAnalysis> found = repo().findLatest("2026-04-20", "pre_tw_open");
        assertTrue(found.isPresent());
        assertEquals("v2", found.get().promptVersion());
        assertEquals("Latest pre-open summary", found.get().summaryText());
    }

    @Test
    void findLatestReturnsEmptyForUnknownSlot() {
        Optional<MarketAnalysis> found = repo().findLatest("2026-04-20", "nonexistent_slot");
        assertTrue(found.isEmpty());
    }

    @Test
    void findLatestHonoursDateFilter() {
        Optional<MarketAnalysis> found = repo().findLatest("2099-12-31", "pre_tw_open");
        assertTrue(found.isEmpty());
    }

    @Test
    void findLatestWorksForDifferentSlot() {
        Optional<MarketAnalysis> found = repo().findLatest("2026-04-20", "us_close");
        assertTrue(found.isPresent());
        assertEquals("Post close summary", found.get().summaryText());
    }

    @Test
    void findLatestAnyReturnsNewestRowAcrossSlots() {
        Optional<MarketAnalysis> found = repo().findLatestAny();
        assertTrue(found.isPresent());
        assertEquals("Latest pre-open summary", found.get().summaryText());
    }

    @Test
    void findLatestAnyIgnoresPushEnabledFlag() {
        jdbc.update("""
                INSERT INTO t_market_analyses
                  (analysis_date, analysis_slot, scheduled_time_local, model, prompt_version,
                   summary_text, raw_json, pushed, push_enabled, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, ?)
                """,
                "2026-04-21", "pre_tw_open", "07:30", "gpt-5", "v3",
                "Disabled but newest", "{}",
                java.sql.Timestamp.valueOf("2026-04-21 08:00:00"));

        Optional<MarketAnalysis> found = repo().findLatestAny();

        assertTrue(found.isPresent());
        assertEquals("Disabled but newest", found.get().summaryText());
    }

    @Test
    void findLatestIgnoresDisabledRows() {
        jdbc.update("""
                INSERT INTO t_market_analyses
                  (analysis_date, analysis_slot, scheduled_time_local, model, prompt_version,
                   summary_text, raw_json, pushed, push_enabled, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, ?)
                """,
                "2026-04-20", "pre_tw_open", "07:30", "gpt-5", "disabled",
                "Disabled newer summary", "{}",
                java.sql.Timestamp.valueOf("2026-04-20 08:00:00"));

        Optional<MarketAnalysis> found = repo().findLatest("2026-04-20", "pre_tw_open");

        assertTrue(found.isPresent());
        assertEquals("v2", found.get().promptVersion());
    }

    @Test
    void findLatestUnpushedSkipsAlreadyPushedRows() {
        jdbc.update("""
                INSERT INTO t_market_analyses
                  (analysis_date, analysis_slot, scheduled_time_local, model, prompt_version,
                   summary_text, raw_json, pushed, push_enabled, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, 1, ?)
                """,
                "2026-04-20", "weekly_tw_preopen", "Sun 05:10", "gpt-5", "v2",
                "Already pushed weekly summary", "{}",
                java.sql.Timestamp.valueOf("2026-04-20 09:00:00"));
        jdbc.update("""
                INSERT INTO t_market_analyses
                  (analysis_date, analysis_slot, scheduled_time_local, model, prompt_version,
                   summary_text, raw_json, pushed, push_enabled, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, 1, ?)
                """,
                "2026-04-20", "weekly_tw_preopen", "Sun 05:10", "gpt-5", "v1",
                "Pending weekly summary", "{}",
                java.sql.Timestamp.valueOf("2026-04-20 08:00:00"));

        Optional<MarketAnalysis> found = repo().findLatestUnpushed("2026-04-20", "weekly_tw_preopen");

        assertTrue(found.isPresent());
        assertEquals("Pending weekly summary", found.get().summaryText());
    }

    @Test
    void markPushedFlipsPushedFlag() {
        Long analysisId = jdbc.queryForObject("""
                SELECT id FROM t_market_analyses
                WHERE analysis_date = '2026-04-20'
                  AND analysis_slot = 'pre_tw_open'
                  AND prompt_version = 'v2'
                ORDER BY updated_at DESC, id DESC
                LIMIT 1
                """, Long.class);
        int updated = repo().markPushed(analysisId);

        assertEquals(1, updated);
        Integer pushed = jdbc.queryForObject("SELECT pushed FROM t_market_analyses WHERE id = ?", Integer.class, analysisId);
        assertEquals(1, pushed);
    }

    @Test
    void disableUnpushedBeforeOnlyDisablesOldEnabledUnpushedRows() {
        int disabled = repo().disableUnpushedBefore("2026-04-21");

        assertEquals(3, disabled);
        Integer remainingEnabledOldUnpushed = jdbc.queryForObject("""
                SELECT COUNT(*) FROM t_market_analyses
                WHERE analysis_date < '2026-04-21'
                  AND pushed = 0
                  AND push_enabled = 1
                """, Integer.class);
        assertEquals(0, remainingEnabledOldUnpushed);
    }
}
