package com.itheima.consultant.service;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * HyDE (Hypothetical Document Embeddings) 服务
 * HyDE是一种增强检索技术：先让LLM生成一个可能包含答案的假设文档，
 * 然后用这个假设文档去检索真实的知识库文档，能更好地匹配语义相关的内容
 *
 * 工作原理：对于复杂问题，用户问题的embedding可能与正确文档的embedding距离较远，
 * 但LLM生成的假设回答文档的embedding更容易命中正确文档，从而提高检索准确率
 */
@Slf4j
@Service
public class HyDEService {

    // 聊天模型，用于生成假设文档
    @Autowired
    @Qualifier("openAiChatModel")
    private ChatModel chatModel;

    // 向量化模型
    @Autowired
    private EmbeddingModel embeddingModel;

    // 向量存储
    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    // 是否启用HyDE
    @Value("${rag.hyde.enabled:true}")
    private boolean enabled;

    // 生成假设文档的数量（通常生成1个即可）
    @Value("${rag.hyde.hypothetical-count:1}")
    private int hypotheticalCount;

    // 假设文档检索返回的最大结果数
    @Value("${rag.hyde.search-top-k:5}")
    private int searchTopK;

    // 最小相似度分数阈值
    @Value("${rag.hyde.min-score:0.5}")
    private double minScore;

    // 提示词：让LLM生成符合要求的假设技术文档片段
    private static final String HYPOTHETICAL_DOC_PROMPT = """
            你是一个专业的风电运维技术文档撰写专家。

            请根据用户的问题，撰写一段可能包含答案的技术文档片段。
            这段文档将用于语义检索，所以请：
            1. 使用专业、准确的技术术语
            2. 包含问题相关的关键信息
            3. 结构清晰，便于检索
            4. 长度控制在200-300字
            5. 直接输出文档内容，不要解释

            用户问题：%s

            技术文档片段：""";

    /**
     * 根据用户查询生成一个假设文档
     * @param query 用户原始查询
     * @return 生成的假设文档，如果生成失败返回null
     */
    public String generateHypotheticalDocument(String query) {
        if (!enabled || query == null || query.trim().isEmpty()) {
            return null;
        }

        log.info("🔄 [HyDE] 生成假设文档，查询: {}", query);

        try {
            String prompt = String.format(HYPOTHETICAL_DOC_PROMPT, query);
            String hypotheticalDoc = chatModel.chat(prompt).trim();

            log.info("✅ [HyDE] 假设文档生成成功，长度: {} 字符", hypotheticalDoc.length());
            log.debug("📄 [HyDE] 假设文档内容: {}", 
                    hypotheticalDoc.length() > 100 ? hypotheticalDoc.substring(0, 100) + "..." : hypotheticalDoc);

            return hypotheticalDoc;

        } catch (Exception e) {
            log.error("❌ [HyDE] 生成假设文档失败", e);
            return null;
        }
    }

    /**
     * 生成多个假设文档（可配置数量）
     * @param query 用户原始查询
     * @return 生成的假设文档列表
     */
    public List<String> generateMultipleHypotheticalDocuments(String query) {
        if (!enabled || query == null || query.trim().isEmpty()) {
            return List.of();
        }

        log.info("🔄 [HyDE] 生成 {} 个假设文档", hypotheticalCount);

        List<String> documents = new ArrayList<>();
        for (int i = 0; i < hypotheticalCount; i++) {
            String doc = generateHypotheticalDocument(query);
            if (doc != null && !doc.isEmpty()) {
                documents.add(doc);
            }
        }

        return documents;
    }

    /**
     * 处理用户查询，生成假设文档并返回检索查询列表
     * 原始查询 + 假设文档一起用于检索，提高召回率
     * @param query 用户原始查询
     * @return HyDE处理结果
     */
    public HyDEResult process(String query) {
        if (!enabled) {
            return new HyDEResult(query, List.of(), List.of());
        }

        log.info("🚀 [HyDE] 开始处理查询: {}", query);

        String hypotheticalDoc = generateHypotheticalDocument(query);

        if (hypotheticalDoc == null || hypotheticalDoc.isEmpty()) {
            log.warn("⚠️ [HyDE] 假设文档生成失败，使用原始查询");
            return new HyDEResult(query, List.of(), List.of());
        }

        List<String> allQueries = new ArrayList<>();
        allQueries.add(query);
        allQueries.add(hypotheticalDoc);

        log.info("✅ [HyDE] 处理完成，生成 {} 个检索查询", allQueries.size());
        return new HyDEResult(query, List.of(hypotheticalDoc), allQueries);
    }

    public record HyDEResult(
            String originalQuery,
            List<String> hypotheticalDocuments,
            List<String> searchQueries
    ) {
        public boolean hasHypotheticalDoc() {
            return hypotheticalDocuments != null && !hypotheticalDocuments.isEmpty();
        }
    }
}
