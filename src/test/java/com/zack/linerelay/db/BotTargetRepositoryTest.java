package com.zack.linerelay.db;

import com.zack.linerelay.config.LineProperties;
import com.zack.linerelay.push.PushModeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H2-backed tests for target filtering and upsert behavior against the same
 * table shape used by the MySQL repositories.
 */
@JdbcTest
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@Sql(scripts = {"/test-schema.sql", "/test-data.sql"})
class BotTargetRepositoryTest {

    private static final String GROUP_ACTIVE_1 = "G_ACTIVE_1";
    private static final String GROUP_NEW = "G_NEW";
    private static final String GROUP_TEST = "G_TEST_GRP";
    private static final String USER_NEW = "U_NEW";
    private static final String USER_TOGGLE = "U_TOGGLE";

    private static final String COUNT_GROUPS_SQL = "SELECT COUNT(*) FROM t_bot_group_info";
    private static final String COUNT_USERS_SQL = "SELECT COUNT(*) FROM t_bot_user_info";
    private static final String SELECT_USER_ACTIVE_SQL =
            "SELECT active FROM t_bot_user_info WHERE user_id = ?";

    @Autowired
    private JdbcTemplate jdbc;

    private BotTargetRepository repo() {
        return repo(true);
    }

    private BotTargetRepository repo(boolean pushTestOnly) {
        // The repository reads test-only mode through PushModeService, matching
        // runtime behavior where webhook commands can change the mode.
        LineProperties props = new LineProperties(
                "s", "t", null,
                new LineProperties.Push(false, pushTestOnly),
                new LineProperties.Mysql(true, "t_market_analyses", "t_bot_group_info", "t_bot_user_info"));
        return new BotTargetRepository(jdbc, props, new PushModeService(props));
    }

    @Test
    void listActiveGroupIdsReturnsEmptyWhenPushTestOnly() {
        List<String> ids = repo().listActiveGroupIds();
        assertEquals(0, ids.size());
    }

    @Test
    void listActiveGroupIdsFiltersInactiveWhenPushTestOnlyOff() {
        List<String> ids = repo(false).listActiveGroupIds();
        assertEquals(2, ids.size());
        assertTrue(ids.contains(GROUP_ACTIVE_1));
        assertTrue(ids.contains("G_ACTIVE_2"));
        assertFalse(ids.contains("G_INACTIVE"));
    }

    @Test
    void listActiveUserIdsOnlyReturnsActiveTestAccounts() {
        List<String> ids = repo().listActiveUserIds();
        assertEquals(1, ids.size());
        assertTrue(ids.contains("U_TEST"));
        assertFalse(ids.contains("U_ACTIVE_1"));
        assertFalse(ids.contains("U_INACTIVE"));
    }

    @Test
    void listActiveTestUserIdsIgnoresPushModeAndOnlyReturnsActiveTestAccounts() {
        List<String> ids = repo(false).listActiveTestUserIds();
        assertEquals(1, ids.size());
        assertTrue(ids.contains("U_TEST"));
        assertFalse(ids.contains("U_ACTIVE_1"));
        assertFalse(ids.contains("U_INACTIVE"));
    }

    @Test
    void listActiveTargetsOnlyUsesActiveTestUsersWhenPushTestOnly() {
        List<BotTarget> targets = repo().listActiveTargets();
        assertEquals(1, targets.size());
        long groups = targets.stream().filter(t -> BotTarget.TYPE_GROUP.equals(t.type())).count();
        long users = targets.stream().filter(t -> BotTarget.TYPE_USER.equals(t.type())).count();
        assertEquals(0, groups);
        assertEquals(1, users);
        assertEquals("U_TEST", targets.get(0).id());
    }

    @Test
    void listActiveTargetsCombinesActiveGroupsAndUsersWhenPushTestOnlyOff() {
        List<BotTarget> targets = repo(false).listActiveTargets();
        assertEquals(4, targets.size());
        long groups = targets.stream().filter(t -> BotTarget.TYPE_GROUP.equals(t.type())).count();
        long users = targets.stream().filter(t -> BotTarget.TYPE_USER.equals(t.type())).count();
        assertEquals(2, groups);
        assertEquals(2, users);
    }

    @Test
    void upsertGroupInsertsNewRowWithActive() {
        repo().upsertGroup(GROUP_NEW, true);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT group_id, test_account, active FROM t_bot_group_info WHERE group_id = ?", GROUP_NEW);
        assertEquals(GROUP_NEW, row.get("GROUP_ID"));
        assertEquals(0, ((Number) row.get("TEST_ACCOUNT")).intValue());
        assertEquals(1, ((Number) row.get("ACTIVE")).intValue());
    }

    @Test
    void upsertGroupUpdatesActiveFlagWithoutInserting() {
        long beforeCount = jdbc.queryForObject(COUNT_GROUPS_SQL, Long.class);

        repo().upsertGroup(GROUP_ACTIVE_1, false);

        long afterCount = jdbc.queryForObject(COUNT_GROUPS_SQL, Long.class);
        assertEquals(beforeCount, afterCount, "existing row should be updated, not duplicated");

        int active = jdbc.queryForObject(
                "SELECT active FROM t_bot_group_info WHERE group_id = ?", Integer.class, GROUP_ACTIVE_1);
        assertEquals(0, active);
    }

    @Test
    void upsertGroupPreservesTestAccountOnUpdate() {
        jdbc.update("INSERT INTO t_bot_group_info (group_id, test_account, active) VALUES (?, 1, 1)", GROUP_TEST);

        repo().upsertGroup(GROUP_TEST, false);

        int testAccount = jdbc.queryForObject(
                "SELECT test_account FROM t_bot_group_info WHERE group_id = ?", Integer.class, GROUP_TEST);
        assertEquals(1, testAccount, "existing test_account=1 must not be downgraded by upsert");
    }

    @Test
    void upsertGroupIgnoresBlankId() {
        long before = jdbc.queryForObject(COUNT_GROUPS_SQL, Long.class);

        repo().upsertGroup("", true);
        repo().upsertGroup(null, true);
        repo().upsertGroup("   ", true);

        long after = jdbc.queryForObject(COUNT_GROUPS_SQL, Long.class);
        assertEquals(before, after);
    }

    @Test
    void upsertUserInsertsNewRowWithActive() {
        repo().upsertUser(USER_NEW, true);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT user_id, test_account, active FROM t_bot_user_info WHERE user_id = ?", USER_NEW);
        assertEquals(USER_NEW, row.get("USER_ID"));
        assertEquals(0, ((Number) row.get("TEST_ACCOUNT")).intValue());
        assertEquals(1, ((Number) row.get("ACTIVE")).intValue());
    }

    @Test
    void upsertUserTogglesActiveState() {
        repo().upsertUser(USER_TOGGLE, true);
        assertEquals(1, jdbc.queryForObject(SELECT_USER_ACTIVE_SQL, Integer.class, USER_TOGGLE));

        repo().upsertUser(USER_TOGGLE, false);
        assertEquals(0, jdbc.queryForObject(SELECT_USER_ACTIVE_SQL, Integer.class, USER_TOGGLE));

        repo().upsertUser(USER_TOGGLE, true);
        assertEquals(1, jdbc.queryForObject(SELECT_USER_ACTIVE_SQL, Integer.class, USER_TOGGLE));
    }

    @Test
    void upsertUserIgnoresBlankId() {
        long before = jdbc.queryForObject(COUNT_USERS_SQL, Long.class);

        repo().upsertUser(null, true);
        repo().upsertUser("", false);

        long after = jdbc.queryForObject(COUNT_USERS_SQL, Long.class);
        assertEquals(before, after);
    }
}
