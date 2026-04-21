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

@JdbcTest
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@Sql(scripts = {"/test-schema.sql", "/test-data.sql"})
class MarketAnalysisRepositoryTest {

    @Autowired
    private JdbcTemplate jdbc;

    private MarketAnalysisRepository repo() {
        LineProperties props = new LineProperties(
                "s", "t", null,
                new LineProperties.Push(false),
                new LineProperties.Mysql(true, "t_market_analyses", "t_bot_group_info", "t_bot_user_info"));
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
        Optional<MarketAnalysis> found = repo().findLatest("2026-04-20", "post_us_close");
        assertTrue(found.isPresent());
        assertEquals("Post close summary", found.get().summaryText());
    }
}
