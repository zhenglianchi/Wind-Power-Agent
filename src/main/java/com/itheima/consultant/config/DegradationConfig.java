package com.itheima.consultant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "rag.degradation")
public class DegradationConfig {

    private DegradationLevel level = DegradationLevel.NORMAL;

    private boolean ragEnabled = true;

    private boolean cacheEnabled = true;

    private boolean toolEnabled = true;

    private String fallbackMessage = "系统当前处于降级模式，部分功能暂时不可用，请稍后再试。";

    public enum DegradationLevel {
        NORMAL,
        DISABLE_CACHE,
        DISABLE_RAG,
        DISABLE_TOOL,
        EMERGENCY
    }

    public boolean isRagAvailable() {
        return level.ordinal() < DegradationLevel.DISABLE_RAG.ordinal() && ragEnabled;
    }

    public boolean isCacheAvailable() {
        return level.ordinal() < DegradationLevel.DISABLE_CACHE.ordinal() && cacheEnabled;
    }

    public boolean isToolAvailable() {
        return level.ordinal() < DegradationLevel.DISABLE_TOOL.ordinal() && toolEnabled;
    }

    public boolean isEmergency() {
        return level == DegradationLevel.EMERGENCY;
    }
}
