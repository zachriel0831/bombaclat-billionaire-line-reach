package com.zack.linerelay.push.dto;

import com.zack.linerelay.push.dto.PushRequest.Message;

import java.util.List;

/**
 * JSON body for LINE `/v2/bot/message/multicast`.
 */
public record MulticastRequest(List<String> to, List<Message> messages) {
}
