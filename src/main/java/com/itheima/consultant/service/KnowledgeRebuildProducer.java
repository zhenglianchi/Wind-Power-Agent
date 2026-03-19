package com.itheima.consultant.service;

import com.itheima.consultant.config.RabbitMQConfig;
import com.itheima.consultant.dto.KnowledgeRebuildStatus;
import com.itheima.consultant.dto.KnowledgeRebuildTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class KnowledgeRebuildProducer {

    private static final String STATUS_KEY_PREFIX = "knowledge:rebuild:status:";

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private final Map<String, KnowledgeRebuildStatus> localStatusCache = new ConcurrentHashMap<>();

    public String submitRebuildTask(boolean clearBeforeRebuild) {
        KnowledgeRebuildTask task = clearBeforeRebuild
                ? KnowledgeRebuildTask.createClearAndRebuild()
                : KnowledgeRebuildTask.createAppend();

        KnowledgeRebuildStatus status = KnowledgeRebuildStatus.pending(task);
        saveStatus(status);

        log.info("📤 [知识库重建] 提交异步任务: taskId={}, type={}",
                task.getTaskId(), task.getTaskType());

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.KNOWLEDGE_REBUILD_EXCHANGE,
                RabbitMQConfig.KNOWLEDGE_REBUILD_ROUTING_KEY,
                task
        );

        return task.getTaskId();
    }

    public KnowledgeRebuildStatus getStatus(String taskId) {
        KnowledgeRebuildStatus status = localStatusCache.get(taskId);
        if (status == null) {
            Object cached = redisTemplate.opsForValue().get(STATUS_KEY_PREFIX + taskId);
            if (cached instanceof KnowledgeRebuildStatus) {
                status = (KnowledgeRebuildStatus) cached;
                localStatusCache.put(taskId, status);
            }
        }
        return status;
    }

    public boolean isRebuildRunning() {
        return localStatusCache.values().stream()
                .anyMatch(s -> KnowledgeRebuildStatus.STATUS_RUNNING.equals(s.getStatus()));
    }

    public String getCurrentRunningTaskId() {
        return localStatusCache.values().stream()
                .filter(s -> KnowledgeRebuildStatus.STATUS_RUNNING.equals(s.getStatus()))
                .findFirst()
                .map(KnowledgeRebuildStatus::getTaskId)
                .orElse(null);
    }

    private void saveStatus(KnowledgeRebuildStatus status) {
        localStatusCache.put(status.getTaskId(), status);
        redisTemplate.opsForValue().set(
                STATUS_KEY_PREFIX + status.getTaskId(),
                status,
                24,
                TimeUnit.HOURS
        );
    }

    @RabbitListener(queues = RabbitMQConfig.KNOWLEDGE_STATUS_QUEUE)
    public void handleStatusUpdate(KnowledgeRebuildStatus status) {
        log.info("📥 [知识库重建] 收到状态更新: taskId={}, status={}, progress={}%",
                status.getTaskId(), status.getStatus(), status.getProgress());

        saveStatus(status);
    }

    public void clearCompletedTasks() {
        localStatusCache.entrySet().removeIf(entry ->
                KnowledgeRebuildStatus.STATUS_COMPLETED.equals(entry.getValue().getStatus()) ||
                KnowledgeRebuildStatus.STATUS_FAILED.equals(entry.getValue().getStatus())
        );
    }
}
