package com.zack.linerelay.push.dto;

import java.util.List;

public record PushRequest(String to, List<Message> messages) {
    public record Message(String type, String text) {
        public static Message text(String text) {
            return new Message("text", text);
        }
    }
}
