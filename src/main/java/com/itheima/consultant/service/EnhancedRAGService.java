package com.itheima.consultant.service;

import com.itheima.consultant.retriever.HybridRerankRetriever;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class EnhancedRAGService {

    @Autowired
    private QueryRewriteService queryRewriteService;

    @Autowired
    private HyDEService hydeService;

    @Autowired
    private HybridRerankRetriever hybridRerankRetriever;

    @Value("${rag.enhanced.enabled:true}")
    private boolean enabled;

    @Value("${rag.enhanced.use-query-rewrite:true}")
    private boolean useQueryRewrite;

    @Value("${rag.enhanced.use-hyde:true}")
    private boolean useHyde;

    @Value("${rag.enhanced.merge-strategy:UNION}")
    private String mergeStrategy;

    public List<Content> retrieve(String originalQuery) {
        log.info("🚀 [增强RAG] 开始检索，原始查询: {}", originalQuery);

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
