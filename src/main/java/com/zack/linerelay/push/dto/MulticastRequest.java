package com.zack.linerelay.push.dto;

import com.zack.linerelay.push.dto.PushRequest.Message;

import java.util.List;

public record MulticastRequest(List<String> to, List<Message> messages) {
}
