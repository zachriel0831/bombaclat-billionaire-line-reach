package com.zack.linerelay.db;

import com.zack.linerelay.config.LineProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "line.mysql", name = "enabled", havingValue = "true")
public class MarketAnalysisRepository {

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

    public Optional<MarketAnalysis> findLatest(String analysisDate, String analysisSlot) {
        String sql = "SELECT id, analysis_date, analysis_slot, scheduled_time_local, model, prompt_version, "
                + "summary_text, raw_json, updated_at FROM " + table
                + " WHERE analysis_date = ? AND analysis_slot = ? "
                + "ORDER BY updated_at DESC, id DESC LIMIT 1";
        List<MarketAnalysis> rows = jdbc.query(sql, ROW_MAPPER, analysisDate, analysisSlot);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
