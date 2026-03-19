package com.itheima.consultant.service;

import com.itheima.consultant.config.RabbitMQConfig;
import com.itheima.consultant.dto.KnowledgeRebuildStatus;
import com.itheima.consultant.dto.KnowledgeRebuildTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class KnowledgeRebuildConsumer {

    @Autowired
    private KnowledgeBaseIngestionService ingestionService;

    @Autowired
    private RagCacheService ragCacheService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @org.springframework.beans.factory.annotation.Value("${rag.index-name:wind-farm-knowledge}")
    private String indexName;

    private final AtomicBoolean isProcessing = new AtomicBoolean(false);

    @RabbitListener(queues = RabbitMQConfig.KNOWLEDGE_REBUILD_QUEUE)
    public void handleRebuildTask(KnowledgeRebuildTask task) {
        log.info("🔨 [知识库重建] 开始处理任务: taskId={}, type={}",
                task.getTaskId(), task.getTaskType());

        if (isProcessing.get()) {
            log.warn("⚠️ [知识库重建] 已有任务在处理中，跳过: taskId={}", task.getTaskId());
            sendStatusUpdate(KnowledgeRebuildStatus.failed(
                    task.getTaskId(),
                    task.getTaskType(),
                    "已有重建任务在处理中，请稍后再试"
            ));
            return;
        }

        isProcessing.set(true);
        long startTime = System.currentTimeMillis();

        try {
            sendStatusUpdate(KnowledgeRebuildStatus.running(task.getTaskId(), task.getTaskType()));

            if (task.isClearBeforeRebuild()) {
                performClearAndRebuild(task, startTime);
            } else {
                performAppend(task, startTime);
            }

        } catch (Exception e) {
            log.error("❌ [知识库重建] 任务失败: taskId={}", task.getTaskId(), e);
            sendStatusUpdate(KnowledgeRebuildStatus.failed(
                    task.getTaskId(),
                    task.getTaskType(),
                    e.getMessage()
            ));
        } finally {
            isProcessing.set(false);
        }
    }

    private void performClearAndRebuild(KnowledgeRebuildTask task, long startTime) throws Exception {
        log.info("🧹 [知识库重建] 执行清空重建流程...");

        sendProgressUpdate(task.getTaskId(), task.getTaskType(), 10, "正在删除旧索引...");

        Boolean dropResult = redisTemplate.execute((RedisConnection connection) -> {
            try {
                Object result = connection.execute(
                        "FT.DROPINDEX",
                        indexName.getBytes(),
                        "DD".getBytes()
                );
                log.info("FT.DROPINDEX 执行结果: {}", result);
                return true;
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("Unknown Index name")) {
                    log.warn("⚠️ 索引 [{}] 不存在，跳过删除步骤。", indexName);
                    return false;
                }
                throw e;
            }
        }, false);

        if (dropResult != null && dropResult) {
            log.info("✅ 旧索引 [{}] 及向量数据删除成功", indexName);
        }

        sendProgressUpdate(task.getTaskId(), task.getTaskType(), 20, "正在清除缓存...");
        ragCacheService.evictAll();
        log.info("✅ 问答缓存已清除");

        sendProgressUpdate(task.getTaskId(), task.getTaskType(), 30, "正在加载文档...");
        log.info("🚀 开始重新加载 PDF 并构建新索引...");

        ingestionService.ingestPdfDocumentsWithCallback(
                (processed, total) -> {
                    int progress = 30 + (int) ((processed * 60.0) / total);
                    sendProgressUpdate(task.getTaskId(), task.getTaskType(), progress,
                            String.format("正在处理文档 (%d/%d)", processed, total));
                }
        );

        sendStatusUpdate(KnowledgeRebuildStatus.completed(
                task.getTaskId(),
                task.getTaskType(),
                startTime,
                0
        ));

        log.info("✅ [知识库重建] 清空重建完成: taskId={}", task.getTaskId());
    }

    private void performAppend(KnowledgeRebuildTask task, long startTime) throws Exception {
        log.info("📥 [知识库重建] 执行追加模式...");

        sendProgressUpdate(task.getTaskId(), task.getTaskType(), 10, "正在加载文档...");

        ingestionService.ingestPdfDocumentsWithCallback(
                (processed, total) -> {
                    int progress = 10 + (int) ((processed * 80.0) / total);
                    sendProgressUpdate(task.getTaskId(), task.getTaskType(), progress,
                            String.format("正在处理文档 (%d/%d)", processed, total));
                }
        );

        sendProgressUpdate(task.getTaskId(), task.getTaskType(), 95, "正在清除缓存...");
        ragCacheService.evictAll();

        sendStatusUpdate(KnowledgeRebuildStatus.completed(
                task.getTaskId(),
                task.getTaskType(),
                startTime,
                0
        ));

        log.info("✅ [知识库重建] 追加完成: taskId={}", task.getTaskId());
    }

    private void sendProgressUpdate(String taskId, String taskType, int progress, String currentStep) {
        KnowledgeRebuildStatus status = KnowledgeRebuildStatus.running(taskId, taskType);
        status.updateProgress(progress, currentStep);
        sendStatusUpdate(status);
    }

    private void sendStatusUpdate(KnowledgeRebuildStatus status) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.KNOWLEDGE_STATUS_EXCHANGE,
                    RabbitMQConfig.KNOWLEDGE_STATUS_ROUTING_KEY,
                    status
            );
        } catch (Exception e) {
            log.error("❌ [知识库重建] 发送状态更新失败: taskId={}", status.getTaskId(), e);
        }
    }
}
