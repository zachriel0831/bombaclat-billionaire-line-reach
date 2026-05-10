package com.zack.linerelay.db;

import com.zack.linerelay.config.LineProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * MySQL adapter for prepared market-analysis summaries.
 */
@Repository
@ConditionalOnProperty(prefix = "line.mysql", name = "enabled", havingValue = "true")
public class MarketAnalysisRepository {

    /**
     * Shared row mapper for both scheduled date/slot queries and manual latest
     * queries. `updated_at` is optional in tests, so it is null-safe.
     */
    private static final RowMapper<MarketAnalysis> ROW_MAPPER = (rs, idx) -> {
        Timestamp updated = rs.getTimestamp("updated_at");
        return new MarketAnalysis(
                rs.getLong("id"),
                rs.getString("analysis_date"),
                rs.getString("analysis_slot"),
                rs.getString("scheduled_time_local"),
                rs.getString("model"),
                rs.getString("prompt_version"),
                rs.getString("summary_text"),
                rs.getString("raw_json"),
                updated == null ? null : updated.toInstant()
        );
    };

    private final JdbcTemplate jdbc;
    private final String table;

    public MarketAnalysisRepository(JdbcTemplate jdbc, LineProperties props) {
        this.jdbc = jdbc;
        this.table = props.mysql().analysisTable();
    }

    /**
     * Selects the newest push-enabled analysis for the scheduled/admin date and
     * slot pair. Disabled rows are ignored so old unpushed rows cannot be sent
     * after the midnight cleanup has retired them.
     */
    public Optional<MarketAnalysis> findLatest(String analysisDate, String analysisSlot) {
        String sql = "SELECT id, analysis_date, analysis_slot, scheduled_time_local, model, prompt_version, "
                + "summary_text, raw_json, updated_at FROM " + table
                + " WHERE analysis_date = ? AND analysis_slot = ? AND COALESCE(push_enabled, 1) = 1 "
                + "ORDER BY updated_at DESC, id DESC LIMIT 1";
        List<MarketAnalysis> rows = jdbc.query(sql, ROW_MAPPER, analysisDate, analysisSlot);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Selects the newest row that is still pending delivery. This is used by the
     * Sunday-night weekly catch-up path so it only retries summaries that never
     * recorded a successful push earlier in the day.
     */
    public Optional<MarketAnalysis> findLatestUnpushed(String analysisDate, String analysisSlot) {
        String sql = "SELECT id, analysis_date, analysis_slot, scheduled_time_local, model, prompt_version, "
                + "summary_text, raw_json, updated_at FROM " + table
                + " WHERE analysis_date = ? AND analysis_slot = ?"
                + " AND COALESCE(push_enabled, 1) = 1"
                + " AND COALESCE(pushed, 0) = 0 "
                + "ORDER BY updated_at DESC, id DESC LIMIT 1";
        List<MarketAnalysis> rows = jdbc.query(sql, ROW_MAPPER, analysisDate, analysisSlot);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Selects the newest analysis across all dates and slots for the LINE
     * `西卡卡推送` manual test command. Intentionally ignores push_enabled so
     * the manual override can force-push the latest row even if midnight
     * cleanup has retired it.
     */
    public Optional<MarketAnalysis> findLatestAny() {
        String sql = "SELECT id, analysis_date, analysis_slot, scheduled_time_local, model, prompt_version, "
                + "summary_text, raw_json, updated_at FROM " + table
                + " ORDER BY updated_at DESC, id DESC LIMIT 1";
        List<MarketAnalysis> rows = jdbc.query(sql, ROW_MAPPER);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Retires unpushed rows before the given analysis date. The cleanup runs at
     * midnight to keep yesterday's failed/missed rows from being picked up later.
     */
    public int disableUnpushedBefore(String beforeAnalysisDate) {
        String sql = "UPDATE " + table
                + " SET push_enabled = 0 "
                + " WHERE analysis_date < ? "
                + " AND COALESCE(pushed, 0) = 0 "
                + " AND COALESCE(push_enabled, 1) = 1";
        return jdbc.update(sql, beforeAnalysisDate);
    }

    /**
     * Marks a row as successfully delivered to at least one target. The relay
     * uses this coarse-grained flag to decide whether a same-day catch-up retry
     * is still needed.
     */
    public int markPushed(long analysisId) {
        String sql = "UPDATE " + table
                + " SET pushed = 1 "
                + " WHERE id = ? AND COALESCE(pushed, 0) = 0";
        return jdbc.update(sql, analysisId);
    }
}
