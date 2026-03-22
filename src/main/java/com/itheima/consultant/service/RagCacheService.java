package com.itheima.consultant.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
@Service
public class RagCacheService {

    private static final String NULL_CACHE_VALUE = "NULL_CACHE_MARKER";
    private static final String LOCK_PREFIX = "rag:cache:lock:";

    private final Cache<String, String> localCache;
    private final StringRedisTemplate redisTemplate;
    private final SemanticCacheService semanticCacheService;
    private final Random random = new Random();

    // 配置参数
    @Value("${rag.cache.l2.key-prefix:rag:cache:answer:}")
    private String redisKeyPrefix;

    @Value("${rag.cache.l2.expire-minutes:60}")
    private int redisExpireMinutes;

    @Value("${rag.cache.enabled:true}")
    private boolean cacheEnabled;

    @Value("${rag.cache.penetration.enabled:true}")
    private boolean penetrationEnabled;

    @Value("${rag.cache.penetration.null-expire-seconds:60}")
    private int nullExpireSeconds;

    @Value("${rag.cache.breakdown.enabled:true}")
    private boolean breakdownEnabled;

    @Value("${rag.cache.breakdown.lock-wait-ms:100}")
    private long lockWaitMs;

    @Value("${rag.cache.breakdown.lock-expire-seconds:10}")
    private int lockExpireSeconds;

    @Value("${rag.cache.avalanche.enabled:true}")
    private boolean avalancheEnabled;

    @Value("${rag.cache.avalanche.random-range-seconds:300}")
    private int randomRangeSeconds;

    // Micrometer 监控指标
    private final Counter l1HitCounter;
    private final Counter l2HitCounter;
    private final Counter semanticHitCounter;
    private final Counter missCounter;
    private final Counter penetrationCounter;
    private final Counter breakdownCounter;
    private final Timer cacheGetTimer;

    public RagCacheService(
            @Value("${rag.cache.l1.max-size:50}") int l1MaxSize,
            @Value("${rag.cache.l1.expire-minutes:5}") int l1ExpireMinutes,
            StringRedisTemplate redisTemplate,
            SemanticCacheService semanticCacheService,
            MeterRegistry meterRegistry) {

        this.redisTemplate = redisTemplate;
        this.semanticCacheService = semanticCacheService;

        this.localCache = Caffeine.newBuilder()
                .maximumSize(l1MaxSize)
                .expireAfterWrite(l1ExpireMinutes, TimeUnit.MINUTES)
                .recordStats()
                .build();

        // 初始化监控指标
        this.l1HitCounter = Counter.builder("rag.cache.hit")
                .tag("level", "L1")
                .description("L1 cache hit count")
                .register(meterRegistry);

        this.l2HitCounter = Counter.builder("rag.cache.hit")
                .tag("level", "L2")
                .description("L2 cache hit count")
                .register(meterRegistry);

        this.semanticHitCounter = Counter.builder("rag.cache.hit")
                .tag("level", "SEMANTIC")
                .description("Semantic cache hit count")
                .register(meterRegistry);

        this.missCounter = Counter.builder("rag.cache.miss")
                .description("Cache miss count")
                .register(meterRegistry);

        this.penetrationCounter = Counter.builder("rag.cache.penetration")
                .description("Cache penetration blocked count")
                .register(meterRegistry);

        this.breakdownCounter = Counter.builder("rag.cache.breakdown")
                .description("Cache breakdown lock acquired count")
                .register(meterRegistry);

        this.cacheGetTimer = Timer.builder("rag.cache.get.duration")
                .description("Cache get operation duration")
                .register(meterRegistry);

        log.info("✅ [RagCacheService] 多级缓存初始化完成 - L1最大条数: {}, L1过期时间: {}分钟, L2过期时间: {}分钟",
                l1MaxSize, l1ExpireMinutes, redisExpireMinutes);
        log.info("🛡️ [RagCacheService] 缓存防护已启用 - 穿透防护: {}, 击穿防护: {}, 雪崩防护: {}",
                penetrationEnabled, breakdownEnabled, avalancheEnabled);
        if (semanticCacheService.isEnabled()) {
            log.info("🧠 [RagCacheService] 语义缓存已启用，相似问题将可命中缓存");
        }
    }

    /**
     * 获取缓存（先 L1 Caffeine，再 L2 Redis）
     *
     * @param question 用户问题
     * @return 缓存的答案，未命中返回 null
     */
    public String get(String question) {
        return cacheGetTimer.record(() -> doGet(question));
    }

    private String doGet(String question) {
        if (!cacheEnabled) {
            return null;
        }

        String cacheKey = generateKey(question);

        // 1. 尝试从 L1 Caffeine 获取（精确匹配）
        String cached = localCache.getIfPresent(cacheKey);
        if (cached != null) {
            if (isNullCache(cached)) {
                log.debug("🚫 [L1空值缓存命中] 问题: {}", truncate(question));
                return null;
            }
            log.debug("✅ [L1缓存命中] 问题: {}", truncate(question));
            l1HitCounter.increment();
            return cached;
        }

        // 2. 尝试从 L2 Redis 获取（精确匹配）
        String redisKey = redisKeyPrefix + cacheKey;
        cached = redisTemplate.opsForValue().get(redisKey);
        if (cached != null) {
            if (isNullCache(cached)) {
                log.debug("🚫 [L2空值缓存命中] 问题: {}", truncate(question));
                localCache.put(cacheKey, cached);
                return null;
            }
            log.debug("✅ [L2缓存命中] 问题: {}", truncate(question));
            l2HitCounter.increment();
            localCache.put(cacheKey, cached);
            return cached;
        }

        // 3. 尝试语义缓存匹配（精确不命中时，查找相似问题）
        if (semanticCacheService.isEnabled()) {
            String semanticAnswer = semanticCacheService.findSimilarAnswer(question);
            if (semanticAnswer != null && !isNullCache(semanticAnswer)) {
                log.info("🧠 [语义缓存命中] 问题: {}", truncate(question));
                semanticHitCounter.increment();
                // 回填到 L1 本地缓存，加速下次访问
                localCache.put(cacheKey, semanticAnswer);
                return semanticAnswer;
            }
        }

        log.debug("❌ [缓存未命中] 问题: {}", truncate(question));
        missCounter.increment();
        return null;
    }

    /**
     * 带击穿防护的获取缓存
     * 使用互斥锁防止热点 Key 过期时大量请求穿透到后端
     *
     * @param question 用户问题
     * @param loader   缓存未命中时的数据加载器
     * @return 缓存的答案或加载的答案
     */
    public String getWithBreakdownProtection(String question, Supplier<String> loader) {
        if (!cacheEnabled || !breakdownEnabled) {
            String cached = get(question);
            if (cached != null) {
                return cached;
            }
            return loader.get();
        }

        String cacheKey = generateKey(question);
        String lockKey = LOCK_PREFIX + cacheKey;

        // 先尝试获取缓存
        String cached = get(question);
        if (cached != null) {
            return cached;
        }

        // 尝试获取分布式锁
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", lockExpireSeconds, TimeUnit.SECONDS);

        if (Boolean.TRUE.equals(locked)) {
            breakdownCounter.increment();
            log.debug("🔒 [击穿防护] 获取锁成功，加载数据: {}", truncate(question));
            try {
                // 获取锁成功，执行加载
                String answer = loader.get();
                put(question, answer);
                return answer;
            } finally {
                redisTemplate.delete(lockKey);
            }
        } else {
            // 未获取到锁，等待并重试获取缓存
            log.debug("⏳ [击穿防护] 等待其他线程加载: {}", truncate(question));
            try {
                Thread.sleep(lockWaitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // 重试获取缓存
            cached = get(question);
            if (cached != null) {
                return cached;
            }

            // 仍然未命中，降级直接加载（不做缓存）
            log.warn("⚠️ [击穿防护] 等待后仍未命中缓存，降级加载: {}", truncate(question));
            return loader.get();
        }
    }

    /**
     * 写入缓存（L1 + L2 + 语义索引）
     *
     * @param question 用户问题
     * @param answer   答案
     */
    public void put(String question, String answer) {
        if (!cacheEnabled) {
            return;
        }

        String cacheKey = generateKey(question);

        // 缓存穿透防护：空值也缓存
        if (answer == null || answer.isEmpty()) {
            if (penetrationEnabled) {
                putNullCache(cacheKey);
                penetrationCounter.increment();
                log.debug("🛡️ [穿透防护] 缓存空值: {}", truncate(question));
            }
            return;
        }

        // 写入 L1 Caffeine
        localCache.put(cacheKey, answer);

        // 写入 L2 Redis（带雪崩防护的随机过期时间）
        String redisKey = redisKeyPrefix + cacheKey;
        int expireSeconds = calculateExpireTime();
        redisTemplate.opsForValue().set(redisKey, answer, expireSeconds, TimeUnit.SECONDS);

        // 添加到语义索引
        if (semanticCacheService.isEnabled()) {
            semanticCacheService.addToIndex(question, cacheKey);
        }

        log.debug("📝 [缓存已写入] 问题: {}, 过期时间: {}秒", truncate(question), expireSeconds);
    }

    /**
     * 缓存空值（穿透防护）
     */
    private void putNullCache(String cacheKey) {
        localCache.put(cacheKey, NULL_CACHE_VALUE);

        String redisKey = redisKeyPrefix + cacheKey;
        redisTemplate.opsForValue().set(redisKey, NULL_CACHE_VALUE, nullExpireSeconds, TimeUnit.SECONDS);
    }

    /**
     * 判断是否为空值缓存
     */
    private boolean isNullCache(String value) {
        return NULL_CACHE_VALUE.equals(value);
    }

    /**
     * 计算过期时间（雪崩防护：随机过期）
     */
    private int calculateExpireTime() {
        int baseExpireSeconds = redisExpireMinutes * 60;

        if (!avalancheEnabled) {
            return baseExpireSeconds;
        }

        // 添加随机偏移，避免大量缓存同时过期
        int randomOffset = random.nextInt(randomRangeSeconds);
        return baseExpireSeconds + randomOffset;
    }

    /**
     * 清除指定问题的缓存
     *
     * @param question 用户问题
     */
    public void evict(String question) {
        String cacheKey = generateKey(question);

        localCache.invalidate(cacheKey);

        String redisKey = redisKeyPrefix + cacheKey;
        redisTemplate.delete(redisKey);

        // 从语义索引移除
        if (semanticCacheService.isEnabled()) {
            semanticCacheService.removeFromIndex(cacheKey);
        }

        log.debug("🗑️ [缓存已清除] 问题: {}", truncate(question));
    }

    /**
     * 清除所有缓存（知识库更新时调用）
     */
    public void evictAll() {
        localCache.invalidateAll();

        var keys = redisTemplate.keys(redisKeyPrefix + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }

        // 清空语义索引
        if (semanticCacheService.isEnabled()) {
            semanticCacheService.clearAll();
        }

        log.info("🗑️ [所有缓存已清除]");
    }

    /**
     * 获取 L1 缓存统计信息
     */
    public CacheStats getStats() {
        return localCache.stats();
    }

    /**
     * 获取缓存统计摘要
     */
    public String getStatsSummary() {
        CacheStats stats = getStats();
        return String.format(
                "L1缓存统计 - 命中率: %.2f%%, 命中次数: %d, 未命中次数: %d, 淘汰次数: %d",
                stats.hitRate() * 100,
                stats.hitCount(),
                stats.missCount(),
                stats.evictionCount()
        );
    }

    /**
     * 生成缓存 Key（MD5 哈希）
     */
    private String generateKey(String question) {
        if (question == null) {
            return "";
        }
        String normalized = question.trim().toLowerCase();
        return DigestUtils.md5DigestAsHex(normalized.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 截断字符串用于日志输出
     */
    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 30 ? s.substring(0, 30) + "..." : s;
    }
}
