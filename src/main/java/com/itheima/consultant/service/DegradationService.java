package com.itheima.consultant.service;

import com.itheima.consultant.config.DegradationConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class DegradationService {

    private final DegradationConfig config;

    private final Map<String, AtomicInteger> errorCounters = new ConcurrentHashMap<>();

    private final Map<String, Long> lastErrorTime = new ConcurrentHashMap<>();

    private final Counter degradationCounter;

    private static final int ERROR_THRESHOLD = 5;

    private static final long ERROR_WINDOW_MS = 60_000;

    @Autowired
    public DegradationService(DegradationConfig config, MeterRegistry meterRegistry) {
        this.config = config;
        this.degradationCounter = Counter.builder("rag.degradation.count")
                .description("Degradation trigger count")
                .register(meterRegistry);

        Gauge.builder("rag.degradation.level", () -> config.getLevel().ordinal())
                .description("Current degradation level")
                .register(meterRegistry);
    }

    public DegradationConfig.DegradationLevel getCurrentLevel() {
        return config.getLevel();
    }

    public void setLevel(DegradationConfig.DegradationLevel level) {
        DegradationConfig.DegradationLevel oldLevel = config.getLevel();
        config.setLevel(level);
        log.warn("⚠️ 降级级别变更: {} -> {}", oldLevel, level);
        degradationCounter.increment();
    }

    public boolean isRagAvailable() {
        return config.isRagAvailable();
    }

    public boolean isCacheAvailable() {
        return config.isCacheAvailable();
    }

    public boolean isToolAvailable() {
        return config.isToolAvailable();
    }

    public boolean isEmergency() {
        return config.isEmergency();
    }

    public String getFallbackMessage() {
        return config.getFallbackMessage();
    }

    public void recordError(String component) {
        String key = "error:" + component;
        errorCounters.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();
        lastErrorTime.put(key, System.currentTimeMillis());

        checkAndDegrade(component);
    }

    public void recordSuccess(String component) {
        String key = "error:" + component;
        AtomicInteger counter = errorCounters.get(key);
        if (counter != null) {
            counter.set(0);
        }
    }

    private void checkAndDegrade(String component) {
        String key = "error:" + component;
        AtomicInteger counter = errorCounters.get(key);
        Long lastError = lastErrorTime.get(key);

        if (counter == null || lastError == null) return;

        if (System.currentTimeMillis() - lastError > ERROR_WINDOW_MS) {
            counter.set(1);
            return;
        }

        int errorCount = counter.get();
        if (errorCount >= ERROR_THRESHOLD) {
            autoDegrade(component);
        }
    }

    private void autoDegrade(String component) {
        log.error("🔴 组件 [{}] 连续错误达到阈值，触发自动降级", component);

        switch (component) {
            case "cache":
                if (config.getLevel().ordinal() < DegradationConfig.DegradationLevel.DISABLE_CACHE.ordinal()) {
                    setLevel(DegradationConfig.DegradationLevel.DISABLE_CACHE);
                }
                break;
            case "rag":
                if (config.getLevel().ordinal() < DegradationConfig.DegradationLevel.DISABLE_RAG.ordinal()) {
                    setLevel(DegradationConfig.DegradationLevel.DISABLE_RAG);
                }
                break;
            case "tool":
                if (config.getLevel().ordinal() < DegradationConfig.DegradationLevel.DISABLE_TOOL.ordinal()) {
                    setLevel(DegradationConfig.DegradationLevel.DISABLE_TOOL);
                }
                break;
            case "llm":
                setLevel(DegradationConfig.DegradationLevel.EMERGENCY);
                break;
        }
    }

    public void reset() {
        setLevel(DegradationConfig.DegradationLevel.NORMAL);
        errorCounters.clear();
        lastErrorTime.clear();
        log.info("✅ 降级状态已重置为正常模式");
    }

    public Map<String, Object> getStatus() {
        return Map.of(
                "level", config.getLevel().name(),
                "ragAvailable", config.isRagAvailable(),
                "cacheAvailable", config.isCacheAvailable(),
                "toolAvailable", config.isToolAvailable(),
                "emergency", config.isEmergency()
        );
    }
}
