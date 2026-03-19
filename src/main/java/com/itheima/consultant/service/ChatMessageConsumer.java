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

@Slf4j
@Service
public class ChatMessageConsumer {

    @Autowired
    private WindFarmAssistant windFarmAssistant;

    @Autowired
    private RagCacheService ragCacheService;

    @Autowired
    private DegradationService degradationService;

    @Autowired
    private RabbitTemplate rabbitTemplate;

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

            if (degradationService.isCacheAvailable()) {
                String cachedAnswer = ragCacheService.get(message.getMessage());
                if (cachedAnswer != null) {
                    log.info("🎯 [消息队列] 缓存命中: messageId={}", message.getMessageId());
                    sendResponse(buildCachedResponse(message, cachedAnswer));
                    return;
                }
            }

            MemoryIdContext.set(message.getMemoryId());

            String answer = windFarmAssistant.chat(message.getMemoryId(), message.getMessage());

            long duration = System.currentTimeMillis() - startTime;
            log.info("🤖 [消息队列] 处理完成: messageId={}, 耗时={}ms",
                    message.getMessageId(), duration);

            degradationService.recordSuccess("llm");

            if (degradationService.isCacheAvailable()) {
                String finalAnswer = answer;
                CompletableFuture.runAsync(() -> ragCacheService.put(message.getMessage(), finalAnswer));
            }

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
