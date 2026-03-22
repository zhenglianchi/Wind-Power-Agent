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

/**
 * 服务降级服务
 * 实现基于错误率的自动降级机制，当某个组件连续出错达到阈值时，
 * 自动逐步关闭功能（先禁用缓存 → 再禁用RAG → 再禁用工具 → 最后紧急降级），
 * 保证系统在部分故障时仍能基本可用，是韧性设计的核心组件
 */
@Slf4j
@Service
public class DegradationService {

    // 降级配置，保存当前降级级别
    private final DegradationConfig config;

    // 各组件错误计数器，key为组件名称，value为当前窗口内错误次数
    private final Map<String, AtomicInteger> errorCounters = new ConcurrentHashMap<>();

    // 记录各组件最后一次错误时间，用于滑动窗口错误统计
    private final Map<String, Long> lastErrorTime = new ConcurrentHashMap<>();

    // Prometheus监控指标：降级触发次数
    private final Counter degradationCounter;

    // 错误阈值：滑动窗口内错误次数达到该值触发降级
    private static final int ERROR_THRESHOLD = 5;

    // 错误统计窗口大小（毫秒），一分钟内连续出错达到阈值触发降级
    private static final long ERROR_WINDOW_MS = 60_000;

    /**
     * 构造函数，初始化配置和监控指标
     */
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

    /**
     * 记录组件错误，检查是否需要触发降级
     * @param component 出错的组件名称
     */
    public void recordError(String component) {
        String key = "error:" + component;
        errorCounters.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();
        lastErrorTime.put(key, System.currentTimeMillis());

        checkAndDegrade(component);
    }

    /**
     * 记录组件成功调用，重置错误计数器
     * @param component 成功的组件名称
     */
    public void recordSuccess(String component) {
        String key = "error:" + component;
        AtomicInteger counter = errorCounters.get(key);
        if (counter != null) {
            counter.set(0);
        }
    }

    /**
     * 检查错误计数，达到阈值则触发自动降级
     * @param component 组件名称
     */
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

    /**
     * 根据出错组件自动逐级降级
     * 降级顺序：NORMAL → DISABLE_CACHE → DISABLE_RAG → DISABLE_TOOL → EMERGENCY
     * @param component 出错组件
     */
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

    /**
     * 重置降级状态，恢复到正常模式
     * 一般在故障恢复后手动调用
     */
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
