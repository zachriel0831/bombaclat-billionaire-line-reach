package com.zack.linerelay.db;

import com.zack.linerelay.config.LineProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
@ConditionalOnProperty(prefix = "line.mysql", name = "enabled", havingValue = "true")
public class BotTargetRepository {

    private final JdbcTemplate jdbc;
    private final String groupTable;
    private final String userTable;

    public BotTargetRepository(JdbcTemplate jdbc, LineProperties props) {
        this.jdbc = jdbc;
        this.groupTable = props.mysql().groupTable();
        this.userTable = props.mysql().userTable();
    }

    public List<String> listActiveGroupIds() {
        return jdbc.queryForList(
                "SELECT group_id FROM " + groupTable + " WHERE active = 1 ORDER BY id",
                String.class);
    }

    public List<String> listActiveUserIds() {
        return jdbc.queryForList(
                "SELECT user_id FROM " + userTable + " WHERE active = 1 ORDER BY id",
                String.class);
    }

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
}
