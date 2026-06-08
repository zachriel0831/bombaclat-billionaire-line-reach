package com.zack.linerelay.db;

import com.zack.linerelay.config.LineProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

/**
 * Reads official macro release-calendar rows and marks reminder delivery state.
 */
@Repository
@ConditionalOnProperty(prefix = "line.mysql", name = "enabled", havingValue = "true")
public class MacroReleaseCalendarRepository {

    private final JdbcTemplate jdbc;
    private final String table;

    public MacroReleaseCalendarRepository(JdbcTemplate jdbc, LineProperties props) {
        this.jdbc = jdbc;
        this.table = props.mysql().macroCalendarTable();
    }

    public List<MacroRelease> findPendingForReminderDate(LocalDate reminderDateTaipei) {
        return jdbc.query("""
                SELECT id, indicator_code, indicator_name, period_label, release_title,
                       release_at_taipei, source_name, source_url, importance
                FROM %s
                WHERE reminder_date_taipei = ?
                  AND reminder_pushed = 0
                ORDER BY release_at_taipei ASC, importance DESC, indicator_code ASC, id ASC
                """.formatted(table),
                (rs, rowNum) -> mapRow(rs),
                Date.valueOf(reminderDateTaipei));
    }

    public int markReminderPushed(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", ids.stream().map(_id -> "?").toList());
        Object[] args = ids.toArray(Object[]::new);
        return jdbc.update("""
                UPDATE %s
                SET reminder_pushed = 1,
                    reminder_pushed_at = CURRENT_TIMESTAMP,
                    reminder_push_status = 'sent',
                    reminder_push_error = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id IN (%s)
                """.formatted(table, placeholders), args);
    }

    public int markReminderFailed(List<Long> ids, String status, String error) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", ids.stream().map(_id -> "?").toList());
        Object[] args = new Object[ids.size() + 2];
        args[0] = status == null || status.isBlank() ? "failed" : status;
        args[1] = truncate(error, 1000);
        for (int i = 0; i < ids.size(); i++) {
            args[i + 2] = ids.get(i);
        }
        return jdbc.update("""
                UPDATE %s
                SET reminder_push_status = ?,
                    reminder_push_error = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id IN (%s)
                """.formatted(table, placeholders), args);
    }

    private MacroRelease mapRow(ResultSet rs) throws SQLException {
        Timestamp releaseAt = rs.getTimestamp("release_at_taipei");
        return new MacroRelease(
                rs.getLong("id"),
                rs.getString("indicator_code"),
                rs.getString("indicator_name"),
                rs.getString("period_label"),
                rs.getString("release_title"),
                releaseAt == null ? null : releaseAt.toLocalDateTime(),
                rs.getString("source_name"),
                rs.getString("source_url"),
                rs.getInt("importance"));
    }

    private static String truncate(String value, int maxChars) {
        if (value == null) {
            return null;
        }
        return value.length() > maxChars ? value.substring(0, maxChars) : value;
    }
}
