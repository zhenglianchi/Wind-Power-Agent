package com.itheima.consultant.repository;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class RedisChatMemoryProvider implements ChatMemoryProvider {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Value("${rag.memory.short-term.window-size:4}")
    private int maxWindowMessages;

    @Value("${rag.memory.short-term.window-ttl-days:1}")
    private int windowTtlDays;

    @Value("${rag.memory.archive-ttl-days:30}")
    private int archiveTtlDays;

    @Override
    public ChatMemory get(Object memoryId) {
        return MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(maxWindowMessages)
                .chatMemoryStore(new ArchivingRedisChatMemoryStore(memoryId))
                .build();
    }

    private class ArchivingRedisChatMemoryStore implements ChatMemoryStore {
        private final Object memoryId;
        private final String memoryKey;
        private final String archiveKey;
        private final Duration windowTtl;
        private final Duration archiveTtl;

        public ArchivingRedisChatMemoryStore(Object memoryId) {
            this.memoryId = memoryId;
            this.memoryKey = "chat:memory:" + memoryId.toString();
            this.archiveKey = "chat:archive:" + memoryId.toString();
            this.windowTtl = Duration.ofDays(windowTtlDays);
            this.archiveTtl = Duration.ofDays(archiveTtlDays);
        }

        @Override
        public List<ChatMessage> getMessages(Object id) {
            String json = redisTemplate.opsForValue().get(memoryKey);
            if (json == null) {
                return new ArrayList<>();
            }
            return ChatMessageDeserializer.messagesFromJson(json);
        }

        @Override
        public void updateMessages(Object id, List<ChatMessage> newMessages) {
            List<ChatMessage> oldMessages = getMessages(id);

            // 如果是首次存储，直接保存，无需归档
            if (oldMessages == null || oldMessages.isEmpty()) {
                saveToMemory(newMessages);
                return;
            }

            // 计算被挤出的消息
            List<ChatMessage> evictedMessages = calculateEvictedMessages(oldMessages, newMessages);

            if (!evictedMessages.isEmpty()) {
                // 读取现有归档
                String archiveJson = redisTemplate.opsForValue().get(archiveKey);
                List<ChatMessage> archivedList = (archiveJson != null)
                        ? ChatMessageDeserializer.messagesFromJson(archiveJson)
                        : new ArrayList<>();

                // 追加新挤出的消息
                archivedList.addAll(evictedMessages);

                // 写回 Redis
                String newArchiveJson = ChatMessageSerializer.messagesToJson(archivedList);
                redisTemplate.opsForValue().set(archiveKey, newArchiveJson, archiveTtl);

                // 打印成功日志
                System.out.println(">>> [SUCCESS] Archived " + evictedMessages.size() + " messages:");
                for (ChatMessage m : evictedMessages) {
                    System.out.println("      - [" + m.type() + "] " + truncate(getText(m)));
                }
            } else {
                System.out.println(">>> [INFO] No messages evicted in this update (Old: " + oldMessages.size() + ", New: " + newMessages.size() + ").");
            }

            // 更新当前窗口
            saveToMemory(newMessages);
        }

        /**
         * 核心逻辑：通过差集计算找出被移除的消息
         */
        private List<ChatMessage> calculateEvictedMessages(List<ChatMessage> oldMessages, List<ChatMessage> newMessages) {
            List<ChatMessage> evicted = new ArrayList<>();

            // 1. 构建新消息的指纹集合
            Set<String> newSigs = new HashSet<>();
            for (ChatMessage msg : newMessages) {
                newSigs.add(getSignature(msg));
            }

            // 2. 遍历旧消息，查找不在新集合中的消息
            for (ChatMessage oldMsg : oldMessages) {
                // 【关键修复】：只跳过 SystemMessage。
                // 之前跳过了包含特定关键词的 AI 消息，导致正常回复无法归档。
                // 现在让所有 User 和 AI 消息都参与比对，确保完整性。
                if (oldMsg instanceof SystemMessage) {
                    continue;
                }

                String oldSig = getSignature(oldMsg);

                // 如果新列表中没有这个指纹，说明它被挤出了
                if (!newSigs.contains(oldSig)) {
                    evicted.add(oldMsg);
                }
            }

            return evicted;
        }

        /**
         * 生成消息的唯一指纹：仅基于文本内容
         * 移除了 type 拼接，避免类型枚举问题，同时防止因类型标记差异导致匹配失败
         */
        private String getSignature(ChatMessage msg) {
            String content = "";

            if (msg instanceof AiMessage) {
                content = ((AiMessage) msg).text();
            } else if (msg instanceof UserMessage) {
                UserMessage userMsg = (UserMessage) msg;
                content = userMsg.contents().stream()
                        .filter(c -> c instanceof TextContent)
                        .map(c -> ((TextContent) c).text())
                        .collect(Collectors.joining("\n"));
            } else if (msg instanceof SystemMessage) {
                content = ((SystemMessage) msg).text();
            } else {
                // 兜底：其他类型直接使用 toString
                content = msg.toString();
            }

            if (content != null) {
                content = content.trim();
            } else {
                content = "";
            }

            return content;
        }

        private String getText(ChatMessage msg) {
            if (msg instanceof AiMessage) return ((AiMessage) msg).text();
            if (msg instanceof SystemMessage) return ((SystemMessage) msg).text();
            if (msg instanceof UserMessage) {
                return ((UserMessage) msg).contents().stream()
                        .filter(c -> c instanceof TextContent)
                        .map(c -> ((TextContent) c).text())
                        .collect(Collectors.joining("\n"));
            }
            return msg.toString();
        }

        private void saveToMemory(List<ChatMessage> messages) {
            if (messages != null && !messages.isEmpty()) {
                String json = ChatMessageSerializer.messagesToJson(messages);
                redisTemplate.opsForValue().set(memoryKey, json, windowTtl);
            }
        }

        @Override
        public void deleteMessages(Object id) {
            redisTemplate.delete(memoryKey);
            // 注意：这里不删除归档，除非业务要求彻底清除
        }

        private String truncate(String s) {
            if (s == null) return "";
            if (s.length() > 50) return s.substring(0, 50) + "...";
            return s;
        }
    }

    /**
     * 获取完整历史（归档 + 当前窗口）
     */
    public List<ChatMessage> getFullHistory(Object memoryId) {
        String archiveKey = "chat:archive:" + memoryId.toString();
        String memoryKey = "chat:memory:" + memoryId.toString();
        List<ChatMessage> fullHistory = new ArrayList<>();

        String archiveJson = redisTemplate.opsForValue().get(archiveKey);
        if (archiveJson != null) {
            fullHistory.addAll(ChatMessageDeserializer.messagesFromJson(archiveJson));
        }

        String memoryJson = redisTemplate.opsForValue().get(memoryKey);
        if (memoryJson != null) {
            fullHistory.addAll(ChatMessageDeserializer.messagesFromJson(memoryJson));
        }

        return fullHistory;
    }

    /**
     * 仅获取归档消息
     */
    public List<ChatMessage> getArchivedMessages(Object memoryId) {
        String archiveKey = "chat:archive:" + memoryId.toString();
        String json = redisTemplate.opsForValue().get(archiveKey);
        if (json == null) {
            return new ArrayList<>();
        }
        return ChatMessageDeserializer.messagesFromJson(json);
    }
}
