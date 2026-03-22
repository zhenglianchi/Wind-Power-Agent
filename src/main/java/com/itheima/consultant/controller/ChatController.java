package com.itheima.consultant.controller;

import com.itheima.consultant.aiservice.WindFarmAssistant;
import com.itheima.consultant.config.DegradationConfig;
import com.itheima.consultant.dto.ChatResponse;
import com.itheima.consultant.service.ChatMessageProducer;
import com.itheima.consultant.service.DegradationService;
import com.itheima.consultant.service.RagCacheService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import com.itheima.consultant.pojo.MemoryIdContext;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ChatController {

    @Autowired
    private WindFarmAssistant windFarmAssistant;

    @Autowired
    private RagCacheService ragCacheService;

    @Autowired
    private DegradationService degradationService;

    @Autowired
    private ChatMessageProducer chatMessageProducer;

    private final Counter ragQueryCounter;
    private final Timer ragQueryTimer;

    public ChatController(MeterRegistry meterRegistry) {
        this.ragQueryCounter = Counter.builder("rag.query.total")
                .description("Total RAG query count")
                .register(meterRegistry);

        this.ragQueryTimer = Timer.builder("rag.query.duration")
                .description("RAG query duration")
                .register(meterRegistry);
    }

    /**
     * 普通聊天请求 - 统一走消息队列异步处理
     * 包含完整的缓存、降级保护和流量削峰
     */
    @PostMapping("/chat")
    public Mono<Map<String, Object>> chat(
            @RequestParam(required = false, defaultValue = "default-session") String memoryId,
            @RequestBody Map<String, String> payload
    ) {
        String message = payload.get("message");

        if (message == null || message.trim().isEmpty()) {
            return Mono.just(Map.of("answer", "问题不能为空"));
        }

        log.info("👤 [Session: {}] 收到用户提问：{}", memoryId, message);

        // 紧急降级：直接返回兜底消息
        if (degradationService.isEmergency()) {
            log.warn("⚠️ 系统处于紧急降级模式");
            return Mono.just(Map.of(
                    "answer", degradationService.getFallbackMessage(),
                    "degraded", true,
                    "level", "EMERGENCY"
            ));
        }

        // 先查缓存，缓存命中直接返回
        if (degradationService.isCacheAvailable()) {
            String cachedAnswer = ragCacheService.get(message);
            if (cachedAnswer != null) {
                log.info("🎯 [Session: {}] 缓存命中", memoryId);
                ragQueryCounter.increment();
                return Mono.just(Map.<String, Object>of(
                        "answer", cachedAnswer,
                        "memoryId", memoryId,
                        "cached", true
                ));
            }
        }

        ragQueryCounter.increment();

        // 普通请求走消息队列异步处理，实现削峰填谷和流量控制
        return Mono.fromFuture(() -> chatMessageProducer.sendChatRequest(memoryId, message))
                .timeout(Duration.ofSeconds(65))
                .map(response -> {
                    if ("ERROR".equals(response.getStatus()) || "DEGRADED".equals(response.getStatus())) {
                        return Map.<String, Object>of(
                                "answer", response.getAnswer() != null ? response.getAnswer() : response.getErrorMessage(),
                                "messageId", response.getMessageId(),
                                "memoryId", response.getMemoryId(),
                                "status", response.getStatus(),
                                "degraded", response.isDegraded()
                        );
                    }
                    return Map.<String, Object>of(
                            "answer", response.getAnswer(),
                            "messageId", response.getMessageId(),
                            "memoryId", response.getMemoryId(),
                            "cached", response.isCached(),
                            "durationMs", response.getDurationMs() != null ? response.getDurationMs() : 0,
                            "status", response.getStatus()
                    );
                })
                .onErrorResume(java.util.concurrent.TimeoutException.class, e -> {
                    log.error("⏰ [消息队列] 请求超时: memoryId={}", memoryId);
                    return Mono.just(Map.of(
                            "answer", "请求处理超时，请稍后重试",
                            "status", "TIMEOUT"
                    ));
                })
                .onErrorResume(Exception.class, e -> {
                    log.error("❌ [消息队列] 处理异常: memoryId={}", memoryId, e);
                    return Mono.just(Map.of(
                            "answer", "系统繁忙，请稍后再试: " + e.getMessage(),
                            "status", "ERROR"
                    ));
                });
    }

    /**
     * 流式聊天输出 - 直接响应，不走消息队列
     * 保持流式体验，依然包含降级和缓存保护
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(
            @RequestParam(required = false, defaultValue = "default-session") String memoryId,
            @RequestBody Map<String, String> payload
    ) {
        String message = payload.get("message");

        if (message == null || message.trim().isEmpty()) {
            return Flux.just("data: {\"error\": \"问题不能为空\"}\n\n");
        }

        log.info("👤 [Session: {}] 收到流式提问：{}", memoryId, message);

        // 紧急降级：直接返回兜底消息
        if (degradationService.isEmergency()) {
            log.warn("⚠️ 系统处于紧急降级模式");
            return Flux.just(
                    "data: {\"content\": \"" + degradationService.getFallbackMessage() + "\", \"done\": true}\n\n"
            );
        }

        ragQueryCounter.increment();

        // 先查缓存，缓存命中直接流式返回
        if (degradationService.isCacheAvailable()) {
            String cachedAnswer = ragCacheService.get(message);
            if (cachedAnswer != null) {
                log.info("🎯 [Session: {}] 缓存命中（流式）", memoryId);
                return Flux.fromArray(cachedAnswer.split(""))
                        .delayElements(Duration.ofMillis(20))
                        .map(ch -> "data: {\"content\": \"" + escapeJson(ch) + "\"}\n\n")
                        .concatWith(Flux.just("data: {\"done\": true, \"cached\": true}\n\n"));
            }
        }

        MemoryIdContext.set(memoryId);

        // 直接流式输出
        return windFarmAssistant.chatStream(memoryId, message)
                .map(chunk -> "data: {\"content\": \"" + escapeJson(chunk) + "\"}\n\n")
                .concatWith(Flux.just("data: {\"done\": true}\n\n"))
                .doOnComplete(() -> {
                    log.info("✅ [Session: {}] 流式响应完成", memoryId);
                    degradationService.recordSuccess("llm");
                })
                .doOnError(e -> {
                    log.error("❌ [Session: {}] 流式响应出错", memoryId, e);
                    degradationService.recordError("llm");
                });
    }

    @GetMapping("/chat")
    public Mono<Map<String, Object>> chatGet(
            @RequestParam(required = false, defaultValue = "default-session") String memoryId,
            @RequestParam String message
    ) {
        return chat(memoryId, Map.of("message", message));
    }

    @GetMapping("/degradation/status")
    public Map<String, Object> getDegradationStatus() {
        return degradationService.getStatus();
    }

    @PostMapping("/degradation/level")
    public Map<String, Object> setDegradationLevel(@RequestParam String level) {
        try {
            DegradationConfig.DegradationLevel newLevel = DegradationConfig.DegradationLevel.valueOf(level.toUpperCase());
            degradationService.setLevel(newLevel);
            return Map.of(
                    "success", true,
                    "message", "降级级别已设置为: " + newLevel,
                    "status", degradationService.getStatus()
            );
        } catch (IllegalArgumentException e) {
            return Map.of(
                    "success", false,
                    "message", "无效的降级级别，可选值: NORMAL, DISABLE_CACHE, DISABLE_RAG, DISABLE_TOOL, EMERGENCY"
            );
        }
    }

    @PostMapping("/degradation/reset")
    public Map<String, Object> resetDegradation() {
        degradationService.reset();
        return Map.of(
                "success", true,
                "message", "降级状态已重置",
                "status", degradationService.getStatus()
        );
    }

    @GetMapping("/cache/stats")
    public Map<String, Object> getCacheStats() {
        return Map.of(
                "summary", ragCacheService.getStatsSummary(),
                "hitRate", ragCacheService.getStats().hitRate(),
                "hitCount", ragCacheService.getStats().hitCount(),
                "missCount", ragCacheService.getStats().missCount(),
                "evictionCount", ragCacheService.getStats().evictionCount()
        );
    }

    @DeleteMapping("/cache")
    public Map<String, String> clearCache() {
        ragCacheService.evictAll();
        return Map.of("message", "所有缓存已清除");
    }

    @GetMapping("/queue/stats")
    public Map<String, Object> getQueueStats() {
        return Map.of(
                "pendingRequests", chatMessageProducer.getPendingRequestCount()
        );
    }

    private String escapeJson(String str) {
        if (str == null) return "";
        return str
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
