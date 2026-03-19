package com.itheima.consultant.config;
import com.itheima.consultant.retriever.HybridRerankRetriever;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CommonConfig {

    /**
     * 2. RAG 检索器
     */
    @Bean
    public ContentRetriever contentRetriever(HybridRerankRetriever hybridRerankRetriever) {
        return hybridRerankRetriever;
    }

}
