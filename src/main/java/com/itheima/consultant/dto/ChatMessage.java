package com.itheima.consultant.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private String messageId;
    private String memoryId;
    private String message;
    private LocalDateTime createTime;
    private int retryCount;
    private int priority;

    public static ChatMessage create(String memoryId, String message) {
        return ChatMessage.builder()
                .messageId(java.util.UUID.randomUUID().toString())
                .memoryId(memoryId)
                .message(message)
                .createTime(LocalDateTime.now())
                .retryCount(0)
                .priority(0)
                .build();
    }
}
