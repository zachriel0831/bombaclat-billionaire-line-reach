package com.zack.linerelay.db;

import com.zack.linerelay.config.LineProperties;
import com.zack.linerelay.push.PushModeService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * MySQL adapter for LINE target tables. It owns active/test-account filtering
 * so push workflows do not duplicate SQL target rules.
 */
@Repository
@ConditionalOnProperty(prefix = "line.mysql", name = "enabled", havingValue = "true")
public class BotTargetRepository {

    private final JdbcTemplate jdbc;
    private final String groupTable;
    private final String userTable;
    private final PushModeService pushModeService;

    public BotTargetRepository(JdbcTemplate jdbc, LineProperties props, PushModeService pushModeService) {
        this.jdbc = jdbc;
        this.groupTable = props.mysql().groupTable();
        this.userTable = props.mysql().userTable();
        this.pushModeService = pushModeService;
    }

    /**
     * Reads the live in-memory push mode instead of static configuration so LINE
     * webhook commands can immediately affect target resolution.
     */
    public boolean isPushTestOnly() {
        return pushModeService.isPushTestOnly();
    }

    /**
     * In test-only mode, group pushes are intentionally suppressed even if group
     * rows are active. This keeps ad hoc tests limited to explicit test users.
     */
    public List<String> listActiveGroupIds() {
        if (isPushTestOnly()) {
            return List.of();
        }
        return jdbc.queryForList(
                "SELECT group_id FROM " + groupTable + " WHERE active = 1 ORDER BY id",
                String.class);
    }

    /**
     * Resolves active user IDs, optionally narrowing to rows explicitly marked
     * as test accounts.
     */
    public List<String> listActiveUserIds() {
        String sql = "SELECT user_id FROM " + userTable + " WHERE active = 1";
        if (isPushTestOnly()) {
            sql += " AND test_account = 1";
        }
        sql += " ORDER BY id";
        return jdbc.queryForList(sql, String.class);
    }

    /**
     * Manual command pushes always use this stricter test-user query and do not
     * depend on the current global push mode.
     */
    public List<String> listActiveTestUserIds() {
        return jdbc.queryForList(
                "SELECT user_id FROM " + userTable + " WHERE active = 1 AND test_account = 1 ORDER BY id",
                String.class);
    }

    /**
     * Builds the final ordered push target list used by normal scheduled/admin
     * market-analysis pushes.
     */
    public List<BotTarget> listActiveTargets() {
        List<BotTarget> targets = new ArrayList<>();
        for (String gid : listActiveGroupIds()) {
            targets.add(BotTarget.group(gid));
        }
        for (String uid : listActiveUserIds()) {
            targets.add(BotTarget.user(uid));
        }
        return targets;
    }

    /**
     * Marks a LINE group/room as active or inactive based on webhook events.
     */
    public void upsertGroup(String groupId, boolean active) {
        upsert(groupTable, "group_id", groupId, active);
    }

    /**
     * Marks a LINE user as active or inactive based on webhook events.
     */
    public void upsertUser(String userId, boolean active) {
        upsert(userTable, "user_id", userId, active);
    }

    /**
     * Updates first so existing `test_account` flags are preserved. Inserts use
     * `test_account = 0` because only humans should promote a row to test mode.
     */
    private void upsert(String table, String idColumn, String id, boolean active) {
        if (id == null || id.isBlank()) {
            return;
        }
        int activeFlag = active ? 1 : 0;
        int rows = jdbc.update(
                "UPDATE " + table + " SET active = ?, updated_at = CURRENT_TIMESTAMP WHERE " + idColumn + " = ?",
                activeFlag, id);
        if (rows > 0) {
            return;
        }
        try {
            jdbc.update(
                    "INSERT INTO " + table + " (" + idColumn + ", test_account, active) VALUES (?, 0, ?)",
                    id, activeFlag);
        } catch (DuplicateKeyException ignored) {
            // concurrent upsert won the race; row exists, next event will correct active state
        }
    }
}
