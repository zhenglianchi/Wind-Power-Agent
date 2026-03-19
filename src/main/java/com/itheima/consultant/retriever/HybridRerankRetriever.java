package com.itheima.consultant.retriever;

import com.hankcs.hanlp.HanLP;
import com.itheima.consultant.service.AliyunRerankService;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class HybridRerankRetriever implements ContentRetriever {
    // 👇 新增：配置开关
    @Value("${rag.memory.inject-long-term:true}")
    private boolean injectLongTermMemory;

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private AliyunRerankService rerankService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    // 复用原有配置
    @Value("${rag.retrieval.top-k-recall:30}")
    private int topKRecall;

    @Value("${rag.retrieval.top-k-final:5}")
    private int topKFinal;

    @Value("${rag.retrieval.min-score:0.4}")
    private double minScore;

    @Value("${rag.retrieval.rerank-threshold:0.5}")
    private double rerankThreshold;

    // 新增关键词检索配置
    @Value("${rag.retrieval.keyword-top-k:20}")
    private int keywordTopK;

    // RRF配置
    @Value("${rag.retrieval.rrf-k:60}")
    private int rrfK;

    // RediSearch索引名称
    @Value("${rag.redis.index-name:embedding-index}")
    private String searchIndex;

    // 新增配置：是否启用模糊匹配兜底
    @Value("${rag.retrieval.keyword.fuzzy-enabled:true}")
    private boolean fuzzyEnabled;

    private static final Pattern ALPHANUMERIC_PATTERN = Pattern.compile("[a-zA-Z0-9]+(?:[-_][a-zA-Z0-9]+)*");


    @Override
    public List<Content> retrieve(Query query) {
        log.info("🚀 [混合检索] 开始处理查询：{}", query.text());

        List<Content> allContents = new ArrayList<>();

        // --- 步骤 1: 原有的向量召回 + 关键词召回 ---
        List<ScoredDocument> candidates = fetchCandidates(query);

        if (!candidates.isEmpty()) {
            // 提取文本准备 Rerank
            List<String> candidateTexts = candidates.stream()
                    .map(ScoredDocument::getText)
                    .distinct()
                    .collect(Collectors.toList());

            // --- 步骤 2: 阿里云 Rerank ---
            log.info("🔄 [Rerank] 正在调用阿里云 GTE-Rerank...");
            List<AliyunRerankService.RerankResult> rerankedResults = rerankService.rerank(query.text(), candidateTexts);

            // --- 步骤 3: 过滤与转换 ---
            List<Content> ragContents = rerankedResults.stream()
                    .filter(r -> r.score >= rerankThreshold)
                    .limit(topKFinal)
                    .map(r -> Content.from(TextSegment.from(r.text)))
                    .toList();

            allContents.addAll(ragContents);
            log.info("✅ [检索] RAG 部分召回 {} 条内容", ragContents.size());
        }

        // --- 最终日志 ---
        log.info("📦 [检索完成] 最终返回上下文总数: {}", allContents.size());

        return allContents;
    }

    /**
     * 获取所有候选文档（向量召回 + 关键词召回）
     */
    private List<ScoredDocument> fetchCandidates(Query query) {
        long fetchStart = System.currentTimeMillis();

        log.info("🚀 [检索开始] 查询内容: {}", query.text());

        // 并行执行两种检索 (注意：这里实际上是串行的，若要真正并行需用 CompletableFuture，但为了日志顺序先保持串行)
        List<ScoredDocument> vectorDocs = vectorSearch(query);
        List<ScoredDocument> keywordDocs = keywordSearch(query);

        // --- 新增：详细打印向量召回结果 ---
        log.info("📦 [向量召回] 共 {} 条", vectorDocs.size());
        if (!vectorDocs.isEmpty()) {
            String vectorSummary = vectorDocs.stream()
                    .limit(5) // 只打印前 5 条
                    .map(d -> String.format("(ID:%s, Score:%.4f, Text:%s...)",
                            d.getId() != null ? d.getId().substring(0, Math.min(8, d.getId().length())) : "null",
                            d.getScore(),
                            StringUtils.abbreviate(d.getText(), 20)))
                    .collect(Collectors.joining(", "));
            log.info("   └─ Top 5: {}", vectorSummary);
            if (vectorDocs.size() > 5) {
                log.info("   └─ ... 还有 {} 条未显示", vectorDocs.size() - 5);
            }
        }

        // --- 新增：详细打印关键词召回结果 ---
        log.info("📦 [关键词召回] 共 {} 条", keywordDocs.size());
        if (!keywordDocs.isEmpty()) {
            String keywordSummary = keywordDocs.stream()
                    .limit(5) // 只打印前 5 条
                    .map(d -> String.format("(ID:%s, Score:%.4f, Text:%s...)",
                            d.getId() != null ? d.getId().substring(0, Math.min(8, d.getId().length())) : "null",
                            d.getScore(),
                            StringUtils.abbreviate(d.getText(), 20)))
                    .collect(Collectors.joining(", "));
            log.info("   └─ Top 5: {}", keywordSummary);
            if (keywordDocs.size() > 5) {
                log.info("   └─ ... 还有 {} 条未显示", keywordDocs.size() - 5);
            }
        }

        // 使用 RRF 合并结果
        List<ScoredDocument> merged = mergeWithRRF(vectorDocs, keywordDocs);

        long fetchCost = System.currentTimeMillis() - fetchStart;

        // --- 新增：合并统计 ---
        long uniqueCount = merged.stream().map(this::getDocId).distinct().count();
        log.info("🔄 [RRF 合并完成] 总耗时: {}ms | 去重后总数: {} | (向量:{} + 关键词:{})",
                fetchCost, uniqueCount, vectorDocs.size(), keywordDocs.size());

        return merged;
    }

    /**
     * 向量检索
     */
    private List<ScoredDocument> vectorSearch(Query query) {
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(embeddingModel.embed(query.text()).content())
                .maxResults(topKRecall)
                .minScore(minScore)
                .build();

        return embeddingStore.search(request).matches().stream()
                .map(match -> new ScoredDocument(
                        match.embedded().text(),
                        match.score(),
                        extractDocId(match)
                ))
                .collect(Collectors.toList());
    }

    /**
     * RediSearch 关键词检索 - 优化版：支持分词 OR 查询 + 模糊兜底
     */
    private List<ScoredDocument> keywordSearch(Query query) {
        long startTime = System.currentTimeMillis();
        String originalQuery = query.text();

        try {
            // 1. 策略一：中文分词 + OR 组合 (提高召回率的核心)
            // 将 "风电项目管理系统" 分词为 "风电", "项目", "管理", "系统"
            // 构建查询语句： "风电 | 项目 | 管理 | 系统"
            List<String> keywords = tokenizeQuery(originalQuery);

            // 过滤掉停用词或过短的词 (可选优化)
            keywords = keywords.stream()
                    .filter(k -> k.length() > 1)
                    .collect(Collectors.toList());

            String searchQueryStr;
            if (!keywords.isEmpty()) {
                // 用 OR (|) 连接，只要包含任意一个词即可命中
                searchQueryStr = "(" + String.join("|", keywords.stream().map(this::escapeQuery).collect(Collectors.toList())) + ")";
                log.info("🔍 [关键字检索] 分词策略 | 原始: {} | 分词后查询: {}", originalQuery, searchQueryStr);
            } else {
                // 如果分词失败或为空，降级为原句
                searchQueryStr = escapeQuery(originalQuery);
                log.warn("⚠️ [关键字检索] 分词结果为空，降级为原句查询: {}", searchQueryStr);
            }

            // 2. 执行检索
            List<ScoredDocument> results = executeRediSearch(searchQueryStr, keywordTopK);

            // 3. 策略二：如果结果太少，启用模糊匹配兜底 (Wildcard)
            // 如果分词查出来的结果少于 3 条，尝试用 *关键词* 这种暴力方式再查一次合并
            if (fuzzyEnabled && results.size() < 3 && keywords.size() > 0) {
                log.info("🔍 [关键字检索] 结果过少 ({} 条), 触发模糊兜底策略...", results.size());

                // 选取最长的 1-2 个核心词进行模糊搜索，避免 *太* 短的词导致全表扫描
                String coreKeyword = keywords.stream()
                        .sorted((a, b) -> Integer.compare(b.length(), a.length()))
                        .findFirst()
                        .orElse("");

                if (StringUtils.isNotEmpty(coreKeyword)) {
                    String fuzzyQuery = "*" + escapeQuery(coreKeyword) + "*";
                    log.info("🔍 [关键字检索] 模糊兜底查询: {}", fuzzyQuery);

                    List<ScoredDocument> fuzzyResults = executeRediSearch(fuzzyQuery, 10); // 兜底只查少量

                    // 合并结果 (去重)
                    Map<String, ScoredDocument> mergedMap = new LinkedHashMap<>();
                    results.forEach(d -> mergedMap.put(getDocId(d), d));
                    fuzzyResults.forEach(d -> {
                        if (!mergedMap.containsKey(getDocId(d))) {
                            // 模糊搜索的分数通常较低，给一个基础分
                            d.setScore(0.3);
                            mergedMap.put(getDocId(d), d);
                        }
                    });

                    results = new ArrayList<>(mergedMap.values());
                    log.info("✅ [关键字检索] 兜底合并后总数: {}", results.size());
                }
            }

            return results;

        } catch (Exception e) {
            // ... 原有异常处理保持不变
            log.error("❌ [关键字检索] 执行失败", e);
            throw new RuntimeException("RediSearch 关键字检索失败", e);
        }
    }

    /**
     * 封装具体的 Redis 执行逻辑，方便复用
     */
    private List<ScoredDocument> executeRediSearch(String queryStr, int limit) {
        String luaScript = String.format(
                "return redis.call('FT.SEARCH', '%s', '%s', 'RETURN', '2', 'id', 'text', 'LIMIT', '0', '%d')",
                escapeLuaString(searchIndex),
                escapeLuaString(queryStr),
                limit
        );

        DefaultRedisScript<List> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(luaScript);
        redisScript.setResultType(List.class);

        List<Object> result = redisTemplate.execute(redisScript, Collections.emptyList());
        return parseRedisSearchResults(result);
    }

    /**
     * 转义Lua字符串中的特殊字符，防止脚本注入
     */
    private String escapeLuaString(String input) {
        if (input == null) return "";
        return input
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    /**
     * 解析RediSearch返回结果 - 修正版
     * RediSearch FT.SEARCH 返回格式: [总数, docId1, fields1, docId2, fields2, ...]
     */
    @SuppressWarnings("unchecked")
    private List<ScoredDocument> parseRedisSearchResults(List<Object> results) {
        long parseStart = System.currentTimeMillis();

        if (results == null || results.size() < 2) {
            log.info("📭 [关键字检索解析] 没有匹配到任何文档");
            return Collections.emptyList();
        }

        List<ScoredDocument> documents = new ArrayList<>();

        try {
            // 第一个元素是总条数
            Object totalObj = results.get(0);
            int total = Integer.parseInt(totalObj.toString());
            log.info("📊 [关键字检索解析] RediSearch报告匹配总数: {}", total);

            // 从索引1开始，每两个元素为一组：[docId, fieldsArray]
            int successCount = 0;
            int failCount = 0;

            for (int i = 1; i < results.size(); i += 2) {
                // i 是 docId (String)
                // i+1 是 fields (List)

                if (i + 1 >= results.size()) {
                    log.warn("⚠️ [关键字检索解析] 数据不完整，缺少字段数组 at index {}", i);
                    break;
                }

                Object docIdObj = results.get(i);
                Object fieldsObj = results.get(i + 1);

                // docId 应该是 String 或 byte[]
                String docId = bytesToString(docIdObj);

                // fields 应该是 List
                if (!(fieldsObj instanceof List)) {
                    log.warn("⚠️ [关键字检索解析] 期望字段数组，实际类型: {} at index {}",
                            fieldsObj.getClass().getName(), i + 1);
                    failCount++;
                    continue;
                }

                List<Object> fieldsList = (List<Object>) fieldsObj;
                String text = null;

                // 解析字段数组 [field1, value1, field2, value2, ...]
                for (int j = 0; j < fieldsList.size(); j += 2) {
                    if (j + 1 >= fieldsList.size()) break;

                    String fieldName = bytesToString(fieldsList.get(j));
                    String fieldValue = bytesToString(fieldsList.get(j + 1));

                    if ("text".equals(fieldName)) {
                        text = fieldValue;
                    }
                }

                if (text != null && !text.isEmpty()) {
                    // 根据排名计算分数 (前20个有分数，后面的分数递减)
                    double score = 1.0 - (double) (i - 1) / Math.min(results.size() - 1, 40);
                    documents.add(new ScoredDocument(text, score, docId));
                    successCount++;

                    log.debug("  [文档{}] ID: {} | 分数: {:.4f} | 文本前30字: {}",
                            (i + 1) / 2, docId, score,
                            text.length() > 30 ? text.substring(0, 30) + "..." : text);
                } else {
                    log.warn("⚠️ [关键字检索解析] 文档 {} 文本为空 | ID: {}", (i + 1) / 2, docId);
                    failCount++;
                }
            }

            long parseCost = System.currentTimeMillis() - parseStart;
            log.info("📈 [关键字检索解析] 完成 | 成功: {} | 失败: {} | 总文档数: {} | 耗时: {}ms",
                    successCount, failCount, documents.size(), parseCost);

        } catch (Exception e) {
            log.error("💥 [关键字检索解析] 解析异常: {}", e.getMessage(), e);
        }

        return documents;
    }

    /**
     * 将字节数组或字符串转换为字符串
     */
    private String bytesToString(Object obj) {
        if (obj == null) return "";
        if (obj instanceof byte[]) {
            return new String((byte[]) obj);
        }
        return obj.toString();
    }

    /**
     * 使用 RRF 算法合并两个结果集
     */
    private List<ScoredDocument> mergeWithRRF(List<ScoredDocument> vectorDocs, List<ScoredDocument> keywordDocs) {
        if (keywordDocs.isEmpty()) {
            log.warn("⚠️ [RRF] 关键词结果为空，直接返回向量结果");
            return vectorDocs;
        }
        if (vectorDocs.isEmpty()) {
            log.warn("⚠️ [RRF] 向量结果为空，直接返回关键词结果");
            return keywordDocs;
        }

        Map<String, ScoredDocument> docMap = new HashMap<>();
        Map<String, Double> rrfScoreMap = new HashMap<>();
        // 新增：记录每个文档的来源，用于日志分析
        Map<String, List<String>> docSourceMap = new HashMap<>();

        // 处理向量召回结果
        for (int i = 0; i < vectorDocs.size(); i++) {
            ScoredDocument doc = vectorDocs.get(i);
            String docId = getDocId(doc);
            double rrfScore = 1.0 / (rrfK + i + 1);
            rrfScoreMap.merge(docId, rrfScore, Double::sum);
            docMap.putIfAbsent(docId, doc);

            // 记录来源
            docSourceMap.computeIfAbsent(docId, k -> new ArrayList<>()).add("Vec(Rank:" + (i+1) + ")");
        }

        // 处理关键词召回结果
        for (int i = 0; i < keywordDocs.size(); i++) {
            ScoredDocument doc = keywordDocs.get(i);
            String docId = getDocId(doc);
            double rrfScore = 1.0 / (rrfK + i + 1);
            rrfScoreMap.merge(docId, rrfScore, Double::sum);
            docMap.putIfAbsent(docId, doc);

            // 记录来源
            docSourceMap.computeIfAbsent(docId, k -> new ArrayList<>()).add("Key(Rank:" + (i+1) + ")");
        }

        // 按 RRF 分数排序返回
        List<ScoredDocument> finalList = rrfScoreMap.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(entry -> {
                    String docId = entry.getKey();
                    ScoredDocument doc = docMap.get(docId);
                    if (doc != null) {
                        doc.setRrfScore(entry.getValue());
                        // 可选：将来源信息暂时存入 id 或其他字段用于调试，或者仅在日志打印
                    }
                    return doc;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // --- 新增：打印 RRF 排序后的详细分析 (Top 10) ---
        log.info("🏆 [RRF 最终排序] Top 10 详情 (格式: [排名] RRF分数 | 来源 | ID摘要 | 文本预览):");
        finalList.stream().limit(10).forEachOrdered(doc -> {
            String docId = getDocId(doc);
            List<String> sources = docSourceMap.getOrDefault(docId, Collections.emptyList());
            String sourceStr = String.join(" + ", sources);

            log.info("   [#{}] RRF:{:.6f} | 来源: {} | ID:{} | 文本:{}",
                    finalList.indexOf(doc) + 1,
                    doc.getRrfScore(),
                    sourceStr,
                    docId.length() > 8 ? docId.substring(0, 8) + "..." : docId,
                    StringUtils.abbreviate(doc.getText(), 30));
        });

        // 统计来源分布
        long onlyVector = finalList.stream()
                .map(this::getDocId)
                .filter(id -> docSourceMap.get(id).size() == 1 && docSourceMap.get(id).get(0).startsWith("Vec"))
                .count();
        long onlyKeyword = finalList.stream()
                .map(this::getDocId)
                .filter(id -> docSourceMap.get(id).size() == 1 && docSourceMap.get(id).get(0).startsWith("Key"))
                .count();
        long both = finalList.size() - onlyVector - onlyKeyword;

        log.info("📊 [RRF 分布统计] 最终列表中共 {} 条 | 仅向量命中: {} | 仅关键词命中: {} | 双重命中 (RRF 增益): {}",
                finalList.size(), onlyVector, onlyKeyword, both);

        return finalList;
    }

    /**
     * 从向量匹配结果中提取文档ID
     */
    private String extractDocId(EmbeddingMatch<TextSegment> match) {
        if (match.embedded() != null &&
                match.embedded().metadata() != null &&
                match.embedded().metadata().getString("id") != null) {
            return match.embedded().metadata().getString("id");
        }
        return "vec_" + Integer.toHexString(match.embedded().text().hashCode());
    }

    /**
     * 获取文档ID
     */
    private String getDocId(ScoredDocument doc) {
        return doc.getId() != null ? doc.getId() :
                "doc_" + Integer.toHexString(doc.getText().hashCode());
    }

    /**
     * 转义RediSearch查询中的特殊字符
     */
    private String escapeQuery(String query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        // RediSearch特殊字符需要转义: ,.<>{}[]"':;!@#$%^&*()-+=~
        return query.replaceAll("([\\\\+\\-&|!(){}\\[\\]^\"~*?:\\/])", "\\\\$1");
    }

    /**
     * 优化的分词工具方法：中文分词 + 英文缩写保护
     */
    private List<String> tokenizeQuery(String text) {
        if (StringUtils.isBlank(text)) return Collections.emptyList();

        Set<String> keywords = new LinkedHashSet<>(); // 使用 Set 去重，同时保持顺序

        // 1. 【关键步骤】提取所有连续的英文/数字串 (保护 ORU, V2.0 等不被拆分)
        Matcher matcher = ALPHANUMERIC_PATTERN.matcher(text);
        while (matcher.find()) {
            String term = matcher.group();
            // 只有当长度>=2 或者是纯大写字母(缩写)时才加入，避免单个字母干扰
            if (term.length() >= 2 || term.matches("[A-Z]")) {
                keywords.add(term);
                // 同时也加入小写版本，防止 RediSearch 大小写敏感导致漏搜
                // 注意：这取决于你的 RediSearch 索引是否建立了大小写不敏感的字段
                // 如果索引区分大小写，这里必须同时加 term.toLowerCase()
                keywords.add(term.toLowerCase());
            }
        }

        // 2. 中文分词 (处理 "风力发电" -> "风力", "发电")
        try {
            List<String> hanlpTerms = HanLP.segment(text).stream()
                    .map(term -> term.word.trim())
                    .filter(StringUtils::isNotBlank)
                    .collect(Collectors.toList());

            for (String term : hanlpTerms) {
                // 过滤掉纯英文（因为上面已经处理过了，避免重复，虽然 Set 会去重）
                // 过滤掉停用词 (可以扩展一个停用词表)
                if (isStopWord(term)) continue;

                // 如果这个词已经被上面的正则提取过且长度一致，跳过（避免 ORU 被 HanLP 拆成 O,R,U 后又被加进来）
                // 但通常我们希望保留中文词，所以直接加即可，Set 会自动去重完全相同的字符串
                keywords.add(term);
            }
        } catch (NoClassDefFoundError e) {
            // 降级逻辑：按空格/标点分割
            String cleaned = text.replaceAll("[，。！？、；：\"'（）()\\[\\]{}<>\\s]+", " ");
            Arrays.stream(cleaned.split("\\s+"))
                    .filter(StringUtils::isNotBlank)
                    .forEach(keywords::add);
        }

        // 3. 【可选】如果原句中包含特定的长实体，也可以把原句作为一个整体加入（防止分词切断语义）
        // 比如 "ORU控制系统"，如果分词成了 "ORU", "控制", "系统"，可能搜不到 "ORU控制系统" 这个完整短语
        // 这里我们可以选择性地加入长度为 2-5 的连续字符组合作为短语
        // 为了性能，这里暂不展开 n-gram，仅依靠 OR 机制通常已足够

        List<String> result = new ArrayList<>(keywords);

        log.debug("🔍 [分词策略] 原始: {} | 最终关键词列表: {}", text, result);

        return result;
    }

    /**
     * 简单的停用词判断 (可根据需要扩展)
     */
    private boolean isStopWord(String term) {
        // 单字且不是英文/数字，通常是停用词或噪音
        if (term.length() == 1 && !term.matches("[a-zA-Z0-9]")) {
            return true;
        }
        // 常见中文停用词
        return "的|了|是|在|就|都|而|及|与|着".split("\\|").length > 0 &&
                java.util.Arrays.asList("的", "了", "是", "在", "就", "都", "而", "及", "与", "着", "吗", "呢").contains(term);
    }

    /**
     * 内部类：带分数的文档
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class ScoredDocument {
        private String text;
        private double score;
        private String id;
        private double rrfScore;

        public ScoredDocument(String text, double score, String id) {
            this.text = text;
            this.score = score;
            this.id = id;
            this.rrfScore = 0.0;
        }
    }
}
