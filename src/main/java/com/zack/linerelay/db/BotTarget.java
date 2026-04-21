package com.zack.linerelay.db;

public record BotTarget(String type, String id) {

    public static final String TYPE_GROUP = "group";
    public static final String TYPE_USER = "user";

    public static BotTarget group(String id) {
        return new BotTarget(TYPE_GROUP, id);
    }

    public static BotTarget user(String id) {
        return new BotTarget(TYPE_USER, id);
    }
}
