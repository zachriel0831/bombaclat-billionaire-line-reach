package com.zack.linerelay.db;

import com.zack.linerelay.config.LineProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@JdbcTest
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@Sql(scripts = {"/test-schema.sql", "/test-data.sql"})
class BotTargetRepositoryTest {

    @Autowired
    private JdbcTemplate jdbc;

    private BotTargetRepository repo() {
        LineProperties props = new LineProperties(
                "s", "t", null,
                new LineProperties.Push(false),
                new LineProperties.Mysql(true, "t_market_analyses", "t_bot_group_info", "t_bot_user_info"));
        return new BotTargetRepository(jdbc, props);
    }

    @Test
    void listActiveGroupIdsFiltersInactive() {
        List<String> ids = repo().listActiveGroupIds();
        assertEquals(2, ids.size());
        assertTrue(ids.contains("G_ACTIVE_1"));
        assertTrue(ids.contains("G_ACTIVE_2"));
        assertTrue(!ids.contains("G_INACTIVE"));
    }

    @Test
    void listActiveUserIdsFiltersInactiveButKeepsTestAccounts() {
        List<String> ids = repo().listActiveUserIds();
        assertEquals(2, ids.size());
        assertTrue(ids.contains("U_ACTIVE_1"));
        assertTrue(ids.contains("U_TEST"));
        assertTrue(!ids.contains("U_INACTIVE"));
    }

    @Test
    void listActiveTargetsCombinesGroupsAndUsersWithType() {
        List<BotTarget> targets = repo().listActiveTargets();
        assertEquals(4, targets.size());
        long groups = targets.stream().filter(t -> BotTarget.TYPE_GROUP.equals(t.type())).count();
        long users = targets.stream().filter(t -> BotTarget.TYPE_USER.equals(t.type())).count();
        assertEquals(2, groups);
        assertEquals(2, users);
    }
}
