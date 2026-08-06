package com.zack.linerelay.push;

/**
 * Business category for outbound LINE messages. The type is part of the Redis
 * quota key so unrelated message families do not consume each other's limits.
 */
public enum PushMessageType {
    PUBLIC_ANALYSIS,
    MACRO_CALENDAR
}
