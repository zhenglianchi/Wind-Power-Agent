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
public class ChatResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private String messageId;
    private String memoryId;
    private String answer;
    private boolean cached;
    private boolean degraded;
    private String degradeLevel;
    private Long durationMs;
    private LocalDateTime createTime;
    private String errorMessage;
    private String status;

    public static ChatResponse success(String messageId, String memoryId, String answer) {
        return ChatResponse.builder()
                .messageId(messageId)
                .memoryId(memoryId)
                .answer(answer)
                .status("SUCCESS")
                .createTime(LocalDateTime.now())
                .build();
    }

    public static ChatResponse error(String messageId, String memoryId, String errorMessage) {
        return ChatResponse.builder()
                .messageId(messageId)
                .memoryId(memoryId)
                .errorMessage(errorMessage)
                .status("ERROR")
                .createTime(LocalDateTime.now())
                .build();
    }
}
