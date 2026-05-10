package com.zack.linerelay.db;

/**
 * Normalized destination used by the push pipeline. LINE accepts user, group,
 * and room IDs for push, but this service currently resolves group and user
 * targets from separate database tables.
 */
public record BotTarget(String type, String id) {

    public static final String TYPE_GROUP = "group";
    public static final String TYPE_USER = "user";

    /**
     * Creates a group/room destination from a stored group-like ID.
     */
    public static BotTarget group(String id) {
        return new BotTarget(TYPE_GROUP, id);
    }

    /**
     * Creates a one-to-one user destination from a stored LINE user ID.
     */
    public static BotTarget user(String id) {
        return new BotTarget(TYPE_USER, id);
    }
}
