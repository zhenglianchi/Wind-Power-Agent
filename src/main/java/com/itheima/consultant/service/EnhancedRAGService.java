package com.itheima.consultant.service;

import com.itheima.consultant.retriever.HybridRerankRetriever;
import com.itheima.consultant.service.DegradationService;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 增强RAG服务
 * 通过查询改写(Query Rewrite)和假设文档生成(HyDE)技术提升检索效果
 *
 * Query Rewrite：将原始问题扩展为多个相关查询，提高召回率
 * HyDE (Hypothetical Document Embeddings)：先生成一个假设的回答文档，
 *      然后用这个文档去检索，能更好地匹配语义相似的真实文档
 */
@Slf4j
@Service
public class EnhancedRAGService {

    // 查询改写服务
    @Autowired
    private QueryRewriteService queryRewriteService;

    // HyDE假设文档生成服务
    @Autowired
    private HyDEService hydeService;

    // 混合检索重排序器
    @Autowired
    private HybridRerankRetriever hybridRerankRetriever;

    // 降级服务
    @Autowired
    private DegradationService degradationService;

    // 是否启用增强RAG
    @Value("${rag.enhanced.enabled:true}")
    private boolean enabled;

    // 是否启用查询改写
    @Value("${rag.enhanced.use-query-rewrite:true}")
    private boolean useQueryRewrite;

    // 是否启用HyDE
    @Value("${rag.enhanced.use-hyde:true}")
    private boolean useHyde;

    // 多查询结果合并策略：UNION(并集)/INTERSECTION(交集)/FIRST_ONLY(仅第一个)
    @Value("${rag.enhanced.merge-strategy:UNION}")
    private String mergeStrategy;

    /**
     * 增强RAG检索入口
     * @param originalQuery 原始用户查询
     * @return 检索到的相关内容列表
     */
    public List<Content> retrieve(String originalQuery) {
        log.info("🚀 [增强RAG] 开始检索，原始查询: {}", originalQuery);

        // 降级检查：如果RAG已禁用，直接返回空结果
        if (!degradationService.isRagAvailable()) {
            log.warn("⚠️ [降级] RAG服务已禁用，跳过检索");
            return Collections.emptyList();
        }

        if (!enabled) {
            return hybridRerankRetriever.retrieve(new Query(originalQuery));
        }

        List<String> searchQueries = new ArrayList<>();
        searchQueries.add(originalQuery);

        if (useQueryRewrite) {
            QueryRewriteService.RewriteResult rewriteResult = queryRewriteService.process(originalQuery);
            if (rewriteResult.expandedQueries() != null) {
                searchQueries.addAll(rewriteResult.expandedQueries());
            }
            log.info("📝 [增强RAG] 查询改写后共 {} 个检索查询", searchQueries.size());
        }

        if (useHyde) {
            HyDEService.HyDEResult hydeResult = hydeService.process(originalQuery);
            if (hydeResult.hasHypotheticalDoc()) {
                searchQueries.addAll(hydeResult.hypotheticalDocuments());
                log.info("📄 [增强RAG] HyDE 生成了 {} 个假设文档", hydeResult.hypotheticalDocuments().size());
            }
        }

        searchQueries = searchQueries.stream().distinct().toList();
        log.info("🔍 [增强RAG] 最终检索查询数: {}", searchQueries.size());

        List<Content> allContents = new ArrayList<>();
        Map<String, Content> contentMap = new LinkedHashMap<>();

        for (String query : searchQueries) {
            try {
                List<Content> contents = hybridRerankRetriever.retrieve(new Query(query));

                switch (mergeStrategy.toUpperCase()) {
                    case "UNION":
                        for (Content content : contents) {
                            String key = content.textSegment().text();
                            if (!contentMap.containsKey(key)) {
                                contentMap.put(key, content);
                            }
                        }
                        break;
                    case "INTERSECTION":
                        if (allContents.isEmpty()) {
                            contents.forEach(c -> contentMap.put(c.textSegment().text(), c));
                        } else {
                            Set<String> currentKeys = contents.stream()
                                    .map(c -> c.textSegment().text())
                                    .collect(Collectors.toSet());
                            contentMap.keySet().retainAll(currentKeys);
                        }
                        break;
                    case "FIRST_ONLY":
                        if (allContents.isEmpty()) {
                            contents.forEach(c -> contentMap.put(c.textSegment().text(), c));
                        }
                        break;
                    default:
                        contents.forEach(c -> contentMap.put(c.textSegment().text(), c));
                }

            } catch (Exception e) {
                log.error("❌ [增强RAG] 查询 '{}' 检索失败", query, e);
            }
        }

        allContents = new ArrayList<>(contentMap.values());

        log.info("✅ [增强RAG] 检索完成，合并后共 {} 条内容", allContents.size());

        return allContents;
    }

    /**
     * 增强RAG检索，返回详细信息（包括改写查询、HyDE文档等）用于调试和监控
     * @param originalQuery 原始用户查询
     * @return 包含检索过程详细信息的结果对象
     */
    public EnhancedRAGResult retrieveWithDetails(String originalQuery) {
        long startTime = System.currentTimeMillis();

        String rewrittenQuery = originalQuery;
        List<String> expandedQueries = List.of();
        List<String> hypotheticalDocs = List.of();

        if (useQueryRewrite) {
            QueryRewriteService.RewriteResult rewriteResult = queryRewriteService.process(originalQuery);
            rewrittenQuery = rewriteResult.rewrittenQuery();
            expandedQueries = rewriteResult.expandedQueries();
        }

        if (useHyde) {
            HyDEService.HyDEResult hydeResult = hydeService.process(originalQuery);
            hypotheticalDocs = hydeResult.hypotheticalDocuments();
        }

        List<Content> contents = retrieve(originalQuery);

        long duration = System.currentTimeMillis() - startTime;

        return new EnhancedRAGResult(
                originalQuery,
                rewrittenQuery,
                expandedQueries,
                hypotheticalDocs,
                contents,
                duration
        );
    }

    public record EnhancedRAGResult(
            String originalQuery,
            String rewrittenQuery,
            List<String> expandedQueries,
            List<String> hypotheticalDocuments,
            List<Content> contents,
            long durationMs
    ) {
        public int totalContents() {
            return contents != null ? contents.size() : 0;
        }

        public boolean hasRewrite() {
            return !originalQuery.equals(rewrittenQuery);
        }

        public boolean hasHyDE() {
            return hypotheticalDocuments != null && !hypotheticalDocuments.isEmpty();
        }
    }
}
