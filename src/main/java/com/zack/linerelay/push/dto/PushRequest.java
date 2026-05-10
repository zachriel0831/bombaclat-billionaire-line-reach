package com.zack.linerelay.push.dto;

import java.util.List;

/**
 * JSON body for LINE `/v2/bot/message/push`.
 */
public record PushRequest(String to, List<Message> messages) {
    /**
     * LINE text message DTO. The static factory keeps callers from repeating the
     * literal `"text"` message type.
     */
    public record Message(String type, String text) {
        public static Message text(String text) {
            return new Message("text", text);
        }
    }
}
