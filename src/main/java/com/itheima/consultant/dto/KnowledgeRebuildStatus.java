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
public class KnowledgeRebuildStatus implements Serializable {

    private static final long serialVersionUID = 1L;

    private String taskId;
    private String taskType;
    private String status;
    private int progress;
    private String currentStep;
    private int totalDocuments;
    private int processedDocuments;
    private Long startTime;
    private Long endTime;
    private Long durationMs;
    private String errorMessage;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    public static KnowledgeRebuildStatus pending(KnowledgeRebuildTask task) {
        return KnowledgeRebuildStatus.builder()
                .taskId(task.getTaskId())
                .taskType(task.getTaskType())
                .status(STATUS_PENDING)
                .progress(0)
                .currentStep("等待处理")
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
    }

    public static KnowledgeRebuildStatus running(String taskId, String taskType) {
        return KnowledgeRebuildStatus.builder()
                .taskId(taskId)
                .taskType(taskType)
                .status(STATUS_RUNNING)
                .progress(0)
                .currentStep("开始处理")
                .startTime(System.currentTimeMillis())
                .updateTime(LocalDateTime.now())
                .build();
    }

    public static KnowledgeRebuildStatus completed(String taskId, String taskType, long startTime, int totalDocs) {
        long endTime = System.currentTimeMillis();
        return KnowledgeRebuildStatus.builder()
                .taskId(taskId)
                .taskType(taskType)
                .status(STATUS_COMPLETED)
                .progress(100)
                .currentStep("处理完成")
                .totalDocuments(totalDocs)
                .processedDocuments(totalDocs)
                .startTime(startTime)
                .endTime(endTime)
                .durationMs(endTime - startTime)
                .updateTime(LocalDateTime.now())
                .build();
    }

    public static KnowledgeRebuildStatus failed(String taskId, String taskType, String errorMessage) {
        return KnowledgeRebuildStatus.builder()
                .taskId(taskId)
                .taskType(taskType)
                .status(STATUS_FAILED)
                .progress(0)
                .currentStep("处理失败")
                .errorMessage(errorMessage)
                .endTime(System.currentTimeMillis())
                .updateTime(LocalDateTime.now())
                .build();
    }

    public void updateProgress(int progress, String currentStep) {
        this.progress = progress;
        this.currentStep = currentStep;
        this.updateTime = LocalDateTime.now();
    }

    public void updateDocuments(int processed, int total) {
        this.processedDocuments = processed;
        this.totalDocuments = total;
        if (total > 0) {
            this.progress = (int) ((processed * 100.0) / total);
        }
        this.updateTime = LocalDateTime.now();
    }
}
