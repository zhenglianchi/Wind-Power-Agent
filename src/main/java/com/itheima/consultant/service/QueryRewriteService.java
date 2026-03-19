package com.itheima.consultant.service;

import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
public class QueryRewriteService {

    @Autowired
    @Qualifier("openAiChatModel")
    private ChatModel chatModel;

    @Value("${rag.query-rewrite.enabled:true}")
    private boolean enabled;

    @Value("${rag.query-rewrite.expand-queries:true}")
    private boolean expandQueries;

    private static final String REWRITE_PROMPT = """
            你是一个专业的查询改写助手，专门用于风电运维领域的知识检索。
            
            请将用户的原始查询改写为更适合检索的形式。改写要求：
            1. 补充必要的专业术语和同义词
            2. 展开缩写和简称（如"E-204"展开为"E-204故障代码"）
            3. 添加相关的上下文关键词
            4. 保持查询的核心意图不变
            5. 输出格式：直接输出改写后的查询，不要解释
            
            原始查询：%s
            
            改写后的查询：""";

    private static final String EXPAND_PROMPT = """
            你是一个专业的查询扩展助手，专门用于风电运维领域的知识检索。
            
            请根据用户的原始查询，生成3个不同角度的扩展查询，以提高检索召回率。
            
            要求：
            1. 每个扩展查询从不同角度描述同一问题
            2. 使用不同的专业术语和表达方式
            3. 保持核心意图一致
            4. 输出格式：每行一个查询，不要编号和解释
            
            原始查询：%s
            
            扩展查询：""";

    public String rewrite(String originalQuery) {
        if (!enabled || originalQuery == null || originalQuery.trim().isEmpty()) {
            return originalQuery;
        }

        log.info("🔄 [查询改写] 原始查询: {}", originalQuery);

        try {
            String prompt = String.format(REWRITE_PROMPT, originalQuery);
            String rewritten = chatModel.chat(prompt).trim();

            log.info("✅ [查询改写] 改写结果: {}", rewritten);
            return rewritten;

        } catch (Exception e) {
            log.error("❌ [查询改写] 改写失败，返回原始查询", e);
            return originalQuery;
        }
    }

    public List<String> expand(String originalQuery) {
        if (!enabled || !expandQueries || originalQuery == null || originalQuery.trim().isEmpty()) {
            return List.of(originalQuery);
        }

        log.info("🔄 [查询扩展] 原始查询: {}", originalQuery);

        try {
            String prompt = String.format(EXPAND_PROMPT, originalQuery);
            String response = chatModel.chat(prompt).trim();

            List<String> expandedQueries = Arrays.stream(response.split("\n"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();

            if (expandedQueries.isEmpty()) {
                return List.of(originalQuery);
            }

            log.info("✅ [查询扩展] 扩展结果: {}", expandedQueries);
            return expandedQueries;

        } catch (Exception e) {
            log.error("❌ [查询扩展] 扩展失败，返回原始查询", e);
            return List.of(originalQuery);
        }
    }

    public RewriteResult process(String originalQuery) {
        if (!enabled) {
            return new RewriteResult(originalQuery, List.of(originalQuery));
        }

        String rewritten = rewrite(originalQuery);
        List<String> expanded = expand(rewritten);

        return new RewriteResult(rewritten, expanded);
    }

    public record RewriteResult(String rewrittenQuery, List<String> expandedQueries) {}
}
