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

@Slf4j
@Service
public class HyDEService {

    @Autowired
    @Qualifier("openAiChatModel")
    private ChatModel chatModel;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    @Value("${rag.hyde.enabled:true}")
    private boolean enabled;

    @Value("${rag.hyde.hypothetical-count:1}")
    private int hypotheticalCount;

    @Value("${rag.hyde.search-top-k:5}")
    private int searchTopK;

    @Value("${rag.hyde.min-score:0.5}")
    private double minScore;

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
