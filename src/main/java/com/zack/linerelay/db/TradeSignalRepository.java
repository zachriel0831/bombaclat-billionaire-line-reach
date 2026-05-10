package com.zack.linerelay.db;

import com.zack.linerelay.config.LineProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * MySQL adapter for the upstream `t_trade_signals` table.
 */
@Repository
@ConditionalOnProperty(prefix = "line.mysql", name = "enabled", havingValue = "true")
public class TradeSignalRepository {

    private static final RowMapper<TradeSignal> ROW_MAPPER = (rs, idx) -> {
        Timestamp updated = rs.getTimestamp("updated_at");
        return new TradeSignal(
                rs.getLong("id"),
                rs.getLong("analysis_id"),
                rs.getString("analysis_date"),
                rs.getString("analysis_slot"),
                rs.getString("market"),
                rs.getString("ticker"),
                rs.getString("name"),
                rs.getString("signal_type"),
                rs.getString("strategy_type"),
                rs.getString("direction"),
                rs.getString("confidence"),
                rs.getString("entry_zone"),
                rs.getString("invalidation"),
                rs.getString("take_profit_zone"),
                rs.getString("holding_horizon"),
                rs.getString("rationale"),
                rs.getString("risk_notes"),
                rs.getString("status"),
                rs.getString("signal_raw_json"),
                rs.getString("analysis_model"),
                rs.getString("analysis_prompt_version"),
                rs.getString("analysis_summary_text"),
                rs.getString("analysis_raw_json"),
                rs.getString("analysis_structured_json"),
                updated == null ? null : updated.toInstant()
        );
    };

    private final JdbcTemplate jdbc;
    private final String table;
    private final String analysisTable;

    public TradeSignalRepository(JdbcTemplate jdbc, LineProperties props) {
        this.jdbc = jdbc;
        this.table = props.mysql().tradeSignalTable();
        this.analysisTable = props.mysql().analysisTable();
    }

    /**
     * Finds the newest active signal for a ticker. Status filtering mirrors the
     * upstream watchlist section so superseded ideas do not get answered later.
     */
    public Optional<TradeSignal> findLatestActiveByTicker(String rawTicker) {
        String ticker = normalizeTicker(rawTicker);
        if (ticker.isEmpty()) {
            return Optional.empty();
        }
        String sql = "SELECT s.id, s.analysis_id, s.analysis_date, s.analysis_slot, s.market, s.ticker, s.name, "
                + "s.signal_type, s.strategy_type, s.direction, s.confidence, s.entry_zone, s.invalidation, "
                + "s.take_profit_zone, s.holding_horizon, s.rationale, s.risk_notes, s.status, "
                + "s.raw_json AS signal_raw_json, a.model AS analysis_model, "
                + "a.prompt_version AS analysis_prompt_version, a.summary_text AS analysis_summary_text, "
                + "a.raw_json AS analysis_raw_json, a.structured_json AS analysis_structured_json, s.updated_at "
                + "FROM " + table + " s "
                + "LEFT JOIN " + analysisTable + " a ON a.id = s.analysis_id "
                + "WHERE UPPER(s.ticker) = ? "
                + "AND s.status IN ('pending_review', 'new', 'watch') "
                + "ORDER BY s.analysis_date DESC, s.updated_at DESC, s.id DESC LIMIT 1";
        List<TradeSignal> rows = jdbc.query(sql, ROW_MAPPER, ticker);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public static String normalizeTicker(String rawTicker) {
        if (rawTicker == null) {
            return "";
        }
        String ticker = rawTicker.trim().toUpperCase(Locale.ROOT);
        if (ticker.startsWith("$")) {
            ticker = ticker.substring(1);
        }
        if (ticker.endsWith(".TW")) {
            ticker = ticker.substring(0, ticker.length() - 3);
        } else if (ticker.endsWith(".TWO")) {
            ticker = ticker.substring(0, ticker.length() - 4);
        }
        return ticker.replaceAll("[^A-Z0-9._-]", "");
    }
}
