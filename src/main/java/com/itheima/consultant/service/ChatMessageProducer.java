package com.itheima.consultant.service;

import com.itheima.consultant.config.RabbitMQConfig;
import com.itheima.consultant.dto.ChatMessage;
import com.itheima.consultant.dto.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ChatMessageProducer {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    private final Map<String, CompletableFuture<ChatResponse>> pendingRequests = new ConcurrentHashMap<>();

    public CompletableFuture<ChatResponse> sendChatRequest(String memoryId, String message) {
        ChatMessage chatMessage = ChatMessage.create(memoryId, message);

        CompletableFuture<ChatResponse> future = new CompletableFuture<>();
        pendingRequests.put(chatMessage.getMessageId(), future);

        try {
            log.info("📤 [消息队列] 发送聊天请求: messageId={}, memoryId={}",
                    chatMessage.getMessageId(), memoryId);

            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.CHAT_EXCHANGE,
                    RabbitMQConfig.CHAT_ROUTING_KEY,
                    chatMessage
            );

            future.orTimeout(60, TimeUnit.SECONDS)
                    .whenComplete((response, ex) -> {
                        pendingRequests.remove(chatMessage.getMessageId());
                        if (ex != null) {
                            log.error("⏰ [消息队列] 请求超时: messageId={}", chatMessage.getMessageId());
                        }
                    });

        } catch (Exception e) {
            log.error("❌ [消息队列] 发送消息失败: messageId={}", chatMessage.getMessageId(), e);
            pendingRequests.remove(chatMessage.getMessageId());
            future.complete(ChatResponse.error(
                    chatMessage.getMessageId(),
                    memoryId,
                    "消息发送失败: " + e.getMessage()
            ));
        }

        return future;
    }

    @RabbitListener(queues = RabbitMQConfig.RESPONSE_QUEUE)
    public void handleResponse(ChatResponse response) {
        CompletableFuture<ChatResponse> future = pendingRequests.remove(response.getMessageId());
        if (future != null) {
            log.info("📥 [消息队列] 收到响应: messageId={}", response.getMessageId());
            future.complete(response);
        } else {
            log.warn("⚠️ [消息队列] 未找到对应的等待请求: messageId={}", response.getMessageId());
        }
    }

    public int getPendingRequestCount() {
        return pendingRequests.size();
    }
}
