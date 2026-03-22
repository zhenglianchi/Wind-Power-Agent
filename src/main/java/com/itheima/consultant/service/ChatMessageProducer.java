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

/**
 * 聊天消息生产者
 * 将聊天请求发送到RabbitMQ异步处理，然后等待响应返回
 * 实现了异步请求-响应模式，解耦发送和接收
 */
@Slf4j
@Service
public class ChatMessageProducer {

    // RabbitMQ模板
    @Autowired
    private RabbitTemplate rabbitTemplate;

    // 保存待处理的请求，消息ID -> CompletableFuture，当响应返回时完成
    private final Map<String, CompletableFuture<ChatResponse>> pendingRequests = new ConcurrentHashMap<>();

    /**
     * 发送聊天请求到消息队列，返回CompletableFuture异步等待结果
     * @param memoryId 对话记忆ID，区分不同会话
     * @param message 用户提问内容
     * @return 可异步等待聊天响应的CompletableFuture
     */
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

    /**
     * 监听响应队列，接收AI处理完成后的响应
     * 根据messageId找到对应的CompletableFuture并完成它
     * @param response 聊天响应
     */
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
