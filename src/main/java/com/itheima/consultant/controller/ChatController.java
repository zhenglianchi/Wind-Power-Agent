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
import java.util.concurrent.CompletableFuture;

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

        if (degradationService.isEmergency()) {
            log.warn("⚠️ 系统处于紧急降级模式");
            return Mono.just(Map.of(
                    "answer", degradationService.getFallbackMessage(),
                    "degraded", true,
                    "level", "EMERGENCY"
            ));
        }

        ragQueryCounter.increment();

        return Mono.fromCallable(() -> {
            if (degradationService.isCacheAvailable()) {
                String cachedAnswer = ragCacheService.get(message);
                if (cachedAnswer != null) {
                    log.info("🎯 [Session: {}] 缓存命中", memoryId);
                    return Map.<String, Object>of(
                            "answer", cachedAnswer,
                            "memoryId", memoryId,
                            "cached", true
                    );
                }
            }

            try {
                long startTime = System.currentTimeMillis();

                MemoryIdContext.set(memoryId);
                String answer = windFarmAssistant.chat(memoryId, message);

                long duration = System.currentTimeMillis() - startTime;
                log.info("🤖 [Session: {}] 回答耗时：{} ms", memoryId, duration);

                degradationService.recordSuccess("llm");

                if (degradationService.isCacheAvailable()) {
                    String finalAnswer = answer;
                    CompletableFuture.runAsync(() -> ragCacheService.put(message, finalAnswer));
                }

                return Map.<String, Object>of(
                        "answer", answer,
                        "memoryId", memoryId,
                        "durationMs", duration
                );

            } catch (Exception e) {
                log.error("❌ [Session: {}] 处理请求时发生异常", memoryId, e);
                degradationService.recordError("llm");
                return Map.<String, Object>of("answer", "系统繁忙，请稍后再试。错误信息：" + e.getMessage());
            }
        }).doOnNext(result -> ragQueryTimer.record(() -> {}));
    }

    @PostMapping("/chat/queue")
    public Mono<Map<String, Object>> chatViaQueue(
            @RequestParam(required = false, defaultValue = "default-session") String memoryId,
            @RequestBody Map<String, String> payload
    ) {
        String message = payload.get("message");

        if (message == null || message.trim().isEmpty()) {
            return Mono.just(Map.of("answer", "问题不能为空"));
        }

        log.info("📤 [消息队列模式] Session: {} 收到用户提问：{}", memoryId, message);

        ragQueryCounter.increment();

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

        if (degradationService.isEmergency()) {
            log.warn("⚠️ 系统处于紧急降级模式");
            return Flux.just(
                    "data: {\"content\": \"" + degradationService.getFallbackMessage() + "\", \"done\": true}\n\n"
            );
        }

        ragQueryCounter.increment();

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

    @PostMapping("/chat/safe")
    public Mono<Map<String, Object>> chatWithProtection(
            @RequestParam(required = false, defaultValue = "default-session") String memoryId,
            @RequestBody Map<String, String> payload
    ) {
        String message = payload.get("message");

        if (message == null || message.trim().isEmpty()) {
            return Mono.just(Map.of("answer", "问题不能为空"));
        }

        log.info("👤 [Session: {}] 收到用户提问（带防护）：{}", memoryId, message);

        if (degradationService.isEmergency()) {
            return Mono.just(Map.of(
                    "answer", degradationService.getFallbackMessage(),
                    "degraded", true
            ));
        }

        ragQueryCounter.increment();

        return Mono.fromCallable(() -> {
            try {
                MemoryIdContext.set(memoryId);

                String answer;
                if (degradationService.isCacheAvailable()) {
                    answer = ragCacheService.getWithBreakdownProtection(message, () -> {
                        long startTime = System.currentTimeMillis();
                        String result = windFarmAssistant.chat(memoryId, message);
                        long duration = System.currentTimeMillis() - startTime;
                        log.info("🤖 [Session: {}] RAG 查询耗时：{} ms", memoryId, duration);
                        return result;
                    });
                } else {
                    answer = windFarmAssistant.chat(memoryId, message);
                }

                degradationService.recordSuccess("llm");

                return Map.<String, Object>of(
                        "answer", answer,
                        "memoryId", memoryId
                );

            } catch (Exception e) {
                log.error("❌ [Session: {}] 处理请求时发生异常", memoryId, e);
                degradationService.recordError("llm");
                return Map.<String, Object>of("answer", "系统繁忙，请稍后再试。错误信息：" + e.getMessage());
            }
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
