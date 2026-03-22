package com.itheima.consultant.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.DefaultRedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 语义缓存服务 - 基于 RediSearch Vector Search 实现
 * 允许语义相似但措辞不同的问题命中缓存，提高缓存命中率
 *
 * 项目已经在使用 RediSearch 做关键词检索，这里复用相同架构做向量相似性搜索
 *
 * 工作原理：
 * 1. 当精确缓存未命中时，计算当前问题的嵌入向量
 * 2. 在 RediSearch 向量索引中搜索 KNN 最相似的已缓存问题
 * 3. 如果相似度超过阈值，返回对应缓存答案
 * 4. 新问题处理完成后，将问题向量写入语义索引
 *
 * 索引创建指引（需要在 Redis Stack 中执行一次）：
 * <pre>
 * FT.CREATE rag_cache_semantic_idx ON HASH PREFIX 1 rag:cache:vector: SCHEMA embedding VECTOR HNSW DIM 1536 DISTANCE_METRIC COSINE
 * </pre>
 *
 * 存储结构（每条为一个 HASH）：
 * <pre>
 * HSET "rag:cache:vector:{md5(question)}"
 *       "embedding"  "(binary vector bytes)"
 *       "question"  "original question text"
 *       "cache_key" "md5(question)"
 * </pre>
 */
@Slf4j
@Service
public class SemanticCacheService {

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Value("${rag.cache.semantic.enabled:true}")
    private boolean enabled;

    @Value("${rag.cache.semantic.min-similarity:0.85}")
    private double minSimilarity;

    @Value("${rag.cache.semantic.max-candidates:10}")
    private int maxCandidates;

    @Value("${rag.cache.semantic.index-name:rag_cache_semantic_idx}")
    private String indexName;

    @Value("${rag.cache.semantic.vector-prefix:rag:cache:vector:}")
    private String vectorKeyPrefix;

    @Value("${rag.community.embedding-store.dimension:1536}")
    private int vectorDimension;

    // 监控指标
    private final Counter semanticHitCounter;
    private final Counter semanticMissCounter;

    public SemanticCacheService(MeterRegistry meterRegistry) {
        this.semanticHitCounter = Counter.builder("rag.cache.semantic.hit")
                .description("Semantic cache hit count")
                .register(meterRegistry);
        this.semanticMissCounter = Counter.builder("rag.cache.semantic.miss")
                .description("Semantic cache miss count")
                .register(meterRegistry);
    }

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("⚠️ [SemanticCache] 语义缓存已禁用");
            return;
        }
        log.info("✅ [SemanticCache] 语义缓存初始化 - 索引: {}, 最小相似度: {}, 最大候选数: {}, 向量维度: {}",
                indexName, minSimilarity, maxCandidates, vectorDimension);

        // 自动检查并创建索引，如果不存在的话
        try {
            boolean indexExists = checkIndexExists();
            if (!indexExists) {
                log.info("🔧 [SemanticCache] 索引 [{}] 不存在，正在自动创建...", indexName);
                createIndex();
                log.info("✅ [SemanticCache] 向量索引 [{}] 创建完成", indexName);
            } else {
                log.info("✅ [SemanticCache] 向量索引 [{}] 已存在", indexName);
            }
        } catch (Exception e) {
            log.warn("⚠️ [SemanticCache] 自动创建索引失败，请手动执行创建命令：" +
                    "FT.CREATE {} ON HASH PREFIX 1 {} SCHEMA embedding VECTOR HNSW DIM {} DISTANCE_METRIC COSINE",
                    indexName, vectorKeyPrefix, vectorDimension, e);
        }
    }

    /**
     * 检查索引是否已经存在
     */
    private boolean checkIndexExists() {
        return redisTemplate.execute((RedisConnection connection) -> {
            try {
                // 执行 FT.INFO 检查索引
                connection.execute("FT.INFO", indexName.getBytes());
                return true;
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("Unknown index name")) {
                    return false;
                }
                throw e;
            }
        }, false);
    }

    /**
     * 自动创建 RediSearch 向量索引
     */
    private void createIndex() {
        redisTemplate.execute((RedisConnection connection) -> {
            // FT.CREATE 命令语法
            // ON HASH - 索引 HASH 类型
            // PREFIX 1 ... - 只索引前缀匹配的 key
            // SCHEMA embedding VECTOR HNSW DIM 1536 DISTANCE_METRIC COSINE
            Object result = connection.execute(
                    "FT.CREATE".getBytes(),
                    indexName.getBytes(),
                    "ON".getBytes(),
                    "HASH".getBytes(),
                    "PREFIX".getBytes(),
                    "1".getBytes(),
                    vectorKeyPrefix.getBytes(),
                    "SCHEMA".getBytes(),
                    "embedding".getBytes(),
                    "VECTOR".getBytes(),
                    "HNSW".getBytes(),
                    "DIM".getBytes(),
                    String.valueOf(vectorDimension).getBytes(),
                    "DISTANCE_METRIC".getBytes(),
                    "COSINE".getBytes()
            );
            log.info("FT.CREATE 执行结果: {}", result);
            return result;
        }, false);
    }

    /**
     * 查找语义相似问题对应的缓存答案
     * @param question 用户当前问题
     * @return 如果找到相似度足够高的缓存问题，返回对应答案；否则返回 null
     */
    public String findSimilarAnswer(String question) {
        if (!enabled) {
            return null;
        }

        try {
            // 1. 计算问题的嵌入向量
            Embedding embedding = embeddingModel.embed(question).content();
            float[] vector = embedding.vector();

            // 2. 使用 RediSearch FT.SEARCH 做 KNN 向量搜索
            List<SearchResult> results = searchKnn(vector);

            if (results.isEmpty()) {
                semanticMissCounter.increment();
                return null;
            }

            // 3. 遍历结果，按相似度排序找到第一个超过阈值的
            for (SearchResult result : results) {
                // RediSearch 返回的是余弦距离 (0~2)，转换为余弦相似度: similarity = 1 - distance/2
                double similarity = 1.0 - (result.distance / 2.0);

                if (similarity >= minSimilarity) {
                    // 提取缓存 key，从向量键中取出 MD5 key
                    String vectorKey = result.key;
                    String cacheKey = result.key.replace(vectorKeyPrefix, "");

                    // 获取原始问题文本（从 HASH 中读取，方便日志
                    Object originalQuestionObj = redisTemplate.opsForHash().get(vectorKey, "question");
                    String originalQuestion = originalQuestionObj != null ? originalQuestionObj.toString() : "unknown";

                    // 答案存储在精确缓存的位置
                    String cachedAnswer = redisTemplate.opsForValue().get(
                            "rag:cache:answer:" + cacheKey
                    );

                    if (cachedAnswer != null && !cachedAnswer.isEmpty()) {
                        log.info("🧠 [语义缓存命中] 当前问题: {} | 匹配原问题: {} | 相似度: {:.4f}",
                                truncate(question), truncate(originalQuestion), similarity);
                        semanticHitCounter.increment();
                        return cachedAnswer;
                    }
                }
            }

            semanticMissCounter.increment();
            return null;

        } catch (Exception e) {
            log.error("❌ [语义缓存] 查询失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 使用 RediSearch 执行 KNN 向量搜索
     */
    private List<SearchResult> searchKnn(float[] vector) {
        // 构建 KNN 查询：*=>[KNN 10 @embedding $vector]
        // 使用 Lua 脚本执行 FT.SEARCH，参数中传递二进制向量
        StringBuilder vectorHex = new StringBuilder();
        for (float f : vector) {
            // 将 float 转换为 4 字节，然后转十六进制
            int bits = Float.floatToIntBits(f);
            vectorHex.append(String.format("%02X%02X%02X%02X",
                    (bits >> 24) & 0xFF,
                    (bits >> 16) & 0xFF,
                    (bits >> 8) & 0xFF,
                    bits & 0xFF));
        }

        // FT.SEARCH 语法 for KNN
        String query = "*=>[KNN " + maxCandidates + " @embedding $blob]";

        String luaScript = String.format(
                "return redis.call('FT.SEARCH', '%s', '%s', 'PARAMS', '2', 'blob', '%s', 'SORTBY', '@embedding', 'ASC', 'RETURN', '1', '__key', 'LIMIT', '0', '%d')",
                escapeLuaString(indexName),
                escapeLuaString(query),
                vectorHex,
                maxCandidates
        );

        DefaultRedisScript<List> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(luaScript);
        redisScript.setResultType(List.class);

        List<Object> result = redisTemplate.execute(redisScript, Collections.emptyList());
        return parseSearchResult(result);
    }

    /**
     * 解析 RediSearch FT.SEARCH 返回结果
     * RediSearch 返回格式: [总数, docId1, score1, docId2, score2, ...]
     */
    private List<SearchResult> parseSearchResult(List<Object> result) {
        List<SearchResult> results = new ArrayList<>();
        if (result == null || result.size() <= 1) {
            return results;
        }

        // 第一个元素是总条数
        for (int i = 1; i < result.size(); i++) {
            Object keyObj = result.get(i);
            String key = bytesToString(keyObj);

            // 下一个元素是分数（距离）
            i++;
            if (i >= result.size()) break;

            Object distanceObj = result.get(i);
            double distance;
            try {
                distance = Double.parseDouble(distanceObj.toString());
            } catch (NumberFormatException e) {
                continue;
            }

            results.add(new SearchResult(key, distance));
        }

        // 结果已经按距离升序排序（RediSearch KNN 返回），所以无需再排序
        log.debug("🔍 [语义搜索] 找到 {} 个候选结果", results.size());
        return results;
    }

    /**
     * 将新问题添加到语义索引
     * @param question 用户问题
     * @param cacheKey 精确缓存的 key（用于关联答案）
     */
    public void addToIndex(String question, String cacheKey) {
        if (!enabled) {
            return;
        }

        try {
            // 计算向量
            Embedding embedding = embeddingModel.embed(question).content();
            float[] vector = embedding.vector();

            // RediSearch 需要存储为 HASH
            String vectorKey = vectorKeyPrefix + cacheKey;

            // 将向量转为字节数组存储
            // 使用 HSET 存储到 Redis，RediSearch 会自动索引
            StringBuilder vectorBlob = new StringBuilder();
            for (float f : vector) {
                int bits = Float.floatToIntBits(f);
                vectorBlob.append((char)((bits >> 24) & 0xFF));
                vectorBlob.append((char)((bits >> 16) & 0xFF));
                vectorBlob.append((char)((bits >> 8) & 0xFF));
                vectorBlob.append((char)(bits & 0xFF));
            }

            // 存储向量 + 原始问题文本
            redisTemplate.opsForHash().put(vectorKey, "embedding", vectorBlob.toString());
            redisTemplate.opsForHash().put(vectorKey, "question", question);
            redisTemplate.opsForHash().put(vectorKey, "cache_key", cacheKey);

            log.debug("📝 [语义缓存] 已添加索引: {} -> {}", truncate(question), cacheKey);

        } catch (Exception e) {
            log.error("❌ [语义缓存] 添加索引失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 从语义索引中移除问题
     * @param cacheKey 缓存 key
     */
    public void removeFromIndex(String cacheKey) {
        if (!enabled) {
            return;
        }
        String vectorKey = vectorKeyPrefix + cacheKey;
        try {
            redisTemplate.delete(vectorKey);
        } catch (Exception e) {
            log.warn("⚠️ [语义缓存] 删除索引失败: {}", vectorKey, e);
        }
    }

    /**
     * 清空整个语义缓存索引
     */
    public void clearAll() {
        if (!enabled) {
            return;
        }
        try {
            var keys = redisTemplate.keys(vectorKeyPrefix + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
            log.info("🗑️ [语义缓存] 已清空所有索引");
        } catch (Exception e) {
            log.error("❌ [语义缓存] 清空失败", e);
        }
    }

    /**
     * 检查语义缓存是否启用
     */
    public boolean isEnabled() {
        return enabled;
    }

    // ========= 工具方法 =========

    private String escapeLuaString(String input) {
        if (input == null) return "";
        return input
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private String bytesToString(Object obj) {
        if (obj == null) return "";
        if (obj instanceof byte[]) {
            return new String((byte[]) obj);
        }
        return obj.toString();
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > 30 ? s.substring(0, 30) + "..." : s;
    }

    /**
     * 搜索结果内部类
     */
    private static class SearchResult {
        private final String key;
        private final double distance;

        public SearchResult(String key, double distance) {
            this.key = key;
            this.distance = distance;
        }
    }
}
