package com.itheima.consultant.service;

import com.itheima.consultant.aiservice.WindFarmAssistant;
import com.itheima.consultant.config.RabbitMQConfig;
import com.itheima.consultant.dto.ChatMessage;
import com.itheima.consultant.dto.ChatResponse;
import com.itheima.consultant.pojo.MemoryIdContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * 聊天消息消费者
 * 从RabbitMQ消息队列接收聊天请求，调用AI助手生成回答，然后将响应发回
 * 采用异步消费模式，实现请求削峰填谷，支持流量控制和降级
 */
@Slf4j
@Service
public class ChatMessageConsumer {

    // 风电场AI助手，核心对话服务
    @Autowired
    private WindFarmAssistant windFarmAssistant;

    // RAG缓存服务，缓存常见问题答案，提高响应速度
    @Autowired
    private RagCacheService ragCacheService;

    // 降级服务，系统负载过高时自动降级，保证可用性
    @Autowired
    private DegradationService degradationService;

    // RabbitMQ模板，用于发送响应回消息队列
    @Autowired
    private RabbitTemplate rabbitTemplate;

    /**
     * 监听聊天请求队列，处理 incoming 聊天请求
     * 处理流程：降级检查 → 缓存查询 → AI回答 → 缓存写入 → 发送响应
     * @param message 聊天消息
     */
    @RabbitListener(queues = RabbitMQConfig.CHAT_QUEUE)
    public void handleChatRequest(ChatMessage message) {
        log.info("📨 [消息队列] 收到聊天请求: messageId={}, memoryId={}",
                message.getMessageId(), message.getMemoryId());

        long startTime = System.currentTimeMillis();

        try {
            if (degradationService.isEmergency()) {
                log.warn("⚠️ [消息队列] 系统处于紧急降级模式");
                sendResponse(buildErrorResponse(message, degradationService.getFallbackMessage(), "EMERGENCY"));
                return;
            }

            MemoryIdContext.set(message.getMemoryId());

            String answer;
            if (degradationService.isCacheAvailable()) {
                // 使用带击穿防护的缓存获取
                answer = ragCacheService.getWithBreakdownProtection(message.getMessage(), () -> {
                    long queryStart = System.currentTimeMillis();
                    String result = windFarmAssistant.chat(message.getMemoryId(), message.getMessage());
                    long queryDuration = System.currentTimeMillis() - queryStart;
                    log.info("🤖 [消息队列] RAG 查询耗时：{} ms", queryDuration);
                    return result;
                });
            } else {
                // 缓存禁用，直接调用
                answer = windFarmAssistant.chat(message.getMemoryId(), message.getMessage());
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("🤖 [消息队列] 处理完成: messageId={}, 总耗时={}ms",
                    message.getMessageId(), duration);

            degradationService.recordSuccess("llm");
            // 写入缓存由 getWithBreakdownProtection 内部完成

            ChatResponse response = ChatResponse.builder()
                    .messageId(message.getMessageId())
                    .memoryId(message.getMemoryId())
                    .answer(answer)
                    .durationMs(duration)
                    .status("SUCCESS")
                    .createTime(java.time.LocalDateTime.now())
                    .build();

            sendResponse(response);

        } catch (Exception e) {
            log.error("❌ [消息队列] 处理消息失败: messageId={}", message.getMessageId(), e);
            degradationService.recordError("llm");

            sendResponse(buildErrorResponse(message, "系统繁忙，请稍后再试: " + e.getMessage(), "ERROR"));

        } finally {
            MemoryIdContext.clear();
        }
    }

    /**
     * 监听死信队列，处理消费失败的消息
     * 进行有限次重试，超过最大重试次数后丢弃
     * @param message 死信消息
     */
    @RabbitListener(queues = RabbitMQConfig.CHAT_DLQ)
    public void handleDeadLetter(ChatMessage message) {
        log.error("💀 [死信队列] 收到死信消息: messageId={}, retryCount={}",
                message.getMessageId(), message.getRetryCount());

        if (message.getRetryCount() < 3) {
            message.setRetryCount(message.getRetryCount() + 1);
            log.info("🔄 [死信队列] 重试消息: messageId={}, retryCount={}",
                    message.getMessageId(), message.getRetryCount());

            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.CHAT_EXCHANGE,
                    RabbitMQConfig.CHAT_ROUTING_KEY,
                    message
            );
        } else {
            log.error("❌ [死信队列] 超过最大重试次数，丢弃消息: messageId={}", message.getMessageId());
        }
    }

    /**
     * 发送响应回消息队列
     * @param response 聊天响应
     */
    private void sendResponse(ChatResponse response) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.RESPONSE_EXCHANGE,
                    RabbitMQConfig.RESPONSE_ROUTING_KEY,
                    response
            );
            log.debug("📤 [消息队列] 发送响应: messageId={}", response.getMessageId());
        } catch (Exception e) {
            log.error("❌ [消息队列] 发送响应失败: messageId={}", response.getMessageId(), e);
        }
    }

    private ChatResponse buildCachedResponse(ChatMessage message, String answer) {
        return ChatResponse.builder()
                .messageId(message.getMessageId())
                .memoryId(message.getMemoryId())
                .answer(answer)
                .cached(true)
                .status("SUCCESS")
                .createTime(java.time.LocalDateTime.now())
                .build();
    }

    private ChatResponse buildErrorResponse(ChatMessage message, String errorMessage, String degradeLevel) {
        return ChatResponse.builder()
                .messageId(message.getMessageId())
                .memoryId(message.getMemoryId())
                .answer(errorMessage)
                .degraded(true)
                .degradeLevel(degradeLevel)
                .status("DEGRADED")
                .createTime(java.time.LocalDateTime.now())
                .build();
    }
}
