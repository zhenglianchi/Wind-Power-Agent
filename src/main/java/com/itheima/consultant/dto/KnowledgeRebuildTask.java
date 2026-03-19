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
public class KnowledgeRebuildTask implements Serializable {

    private static final long serialVersionUID = 1L;

    private String taskId;
    private String taskType;
    private boolean clearBeforeRebuild;
    private LocalDateTime createTime;
    private String triggeredBy;

    public static KnowledgeRebuildTask createAppend() {
        return KnowledgeRebuildTask.builder()
                .taskId(java.util.UUID.randomUUID().toString())
                .taskType("APPEND")
                .clearBeforeRebuild(false)
                .createTime(LocalDateTime.now())
                .triggeredBy("MANUAL")
                .build();
    }

    public static KnowledgeRebuildTask createClearAndRebuild() {
        return KnowledgeRebuildTask.builder()
                .taskId(java.util.UUID.randomUUID().toString())
                .taskType("CLEAR_AND_REBUILD")
                .clearBeforeRebuild(true)
                .createTime(LocalDateTime.now())
                .triggeredBy("MANUAL")
                .build();
    }
}
