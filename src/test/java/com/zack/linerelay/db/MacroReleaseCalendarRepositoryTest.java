package com.zack.linerelay.db;

import com.zack.linerelay.config.LineProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@JdbcTest
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@Sql(scripts = {"/test-schema.sql", "/test-data.sql"})
class MacroReleaseCalendarRepositoryTest {

    @Autowired
    private JdbcTemplate jdbc;

    private MacroReleaseCalendarRepository repo() {
        LineProperties props = new LineProperties(
                "s", "t", null,
                new LineProperties.Push(false, true),
                new LineProperties.Mysql(true, "t_market_analyses", "t_bot_group_info", "t_bot_user_info",
                        "t_trade_signals", "t_macro_release_calendar"));
        return new MacroReleaseCalendarRepository(jdbc, props);
    }

    @Test
    void findPendingForReminderDateReturnsUnpushedRowsInReleaseOrder() {
        insertRelease(1, "cpi-key", "us_cpi", "U.S. CPI", "May 2026",
                "2026-06-10 20:30:00", "2026-06-09", 0);
        insertRelease(2, "ppi-key", "us_ppi", "U.S. PPI", "May 2026",
                "2026-06-11 20:30:00", "2026-06-10", 0);
        insertRelease(3, "old-key", "us_retail_sales", "U.S. Retail Sales", "April 2026",
                "2026-06-10 19:30:00", "2026-06-09", 1);

        List<MacroRelease> rows = repo().findPendingForReminderDate(LocalDate.parse("2026-06-09"));

        assertEquals(1, rows.size());
        assertEquals("us_cpi", rows.get(0).indicatorCode());
        assertEquals("May 2026", rows.get(0).periodLabel());
        assertEquals("2026-06-10T20:30", rows.get(0).releaseAtTaipei().toString());
    }

    @Test
    void markReminderPushedUpdatesDeliveryState() {
        insertRelease(9, "nfp-key", "us_nonfarm_payrolls", "U.S. Nonfarm Payrolls", "June 2026",
                "2026-07-02 20:30:00", "2026-07-01", 0);

        int updated = repo().markReminderPushed(List.of(9L));

        assertEquals(1, updated);
        Integer pushed = jdbc.queryForObject(
                "SELECT reminder_pushed FROM t_macro_release_calendar WHERE id = 9", Integer.class);
        String status = jdbc.queryForObject(
                "SELECT reminder_push_status FROM t_macro_release_calendar WHERE id = 9", String.class);
        assertEquals(1, pushed);
        assertEquals("sent", status);
    }

    private void insertRelease(
            long id,
            String eventKey,
            String indicatorCode,
            String indicatorName,
            String periodLabel,
            String releaseAtTaipei,
            String reminderDate,
            int reminderPushed
    ) {
        jdbc.update("""
                INSERT INTO t_macro_release_calendar
                  (id, event_key, source_id, source_name, indicator_code, indicator_name,
                   period_label, release_title, release_at_utc, release_at_taipei,
                   release_timezone, importance, reminder_date_taipei, reminder_pushed,
                   source_url, raw_json)
                VALUES (?, ?, 'bls', 'U.S. Bureau of Labor Statistics', ?, ?,
                        ?, ?, TIMESTAMP '2026-06-10 12:30:00', ?, 'America/New_York',
                        5, ?, ?, 'https://example.test', '{}')
                """,
                id, eventKey, indicatorCode, indicatorName, periodLabel,
                indicatorName + " for " + periodLabel,
                java.sql.Timestamp.valueOf(releaseAtTaipei),
                java.sql.Date.valueOf(reminderDate),
                reminderPushed);
    }
}
