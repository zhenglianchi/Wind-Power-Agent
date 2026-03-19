package com.itheima.consultant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class AliyunRerankService {

    private final WebClient webClient;
    private final String modelName;
    private final String apiPath;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public static class RerankResult {
        public String text;
        public double score;
        public int index;

        public RerankResult(String text, double score, int index) {
            this.text = text;
            this.score = score;
            this.index = index;
        }

        @Override
        public String toString() {
            return "RerankResult{text='" + text + "', score=" + score + ", index=" + index + "}";
        }
    }

    public AliyunRerankService(
            @Value("${langchain4j.open-ai.rerank-model.base-url}") String baseUrl,
            @Value("${langchain4j.open-ai.rerank-model.api-key}") String apiKey,
            @Value("${langchain4j.open-ai.rerank-model.model-name}") String modelName,
            @Value("${rag.rerank.api-path:/text-rerank/text-rerank}") String apiPath) {

        this.modelName = modelName;
        this.apiPath = apiPath;

        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * 调用阿里云 Rerank API
     */
    public List<RerankResult> rerank(String query, List<String> documents) {
        if (documents == null || documents.isEmpty()) {
            return new ArrayList<>();
        }

        try {
            // 2. 使用 Map 构建符合阿里云官方文档的 JSON 结构
            // 结构参考: { "model": "...", "input": { "query": "...", "documents": [...] }, "parameters": { ... } }
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", modelName);

            // 构建 input 对象
            Map<String, Object> input = new HashMap<>();
            input.put("query", query);
            input.put("documents", documents); // 直接放 List<String>，Jackson 会自动转为 JSON 数组
            requestBody.put("input", input);

            // 构建 parameters 对象
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("return_documents", true);
            parameters.put("top_n", documents.size()); // 或者指定一个固定值，如 5
            requestBody.put("parameters", parameters);

            log.debug("🚀 发送 Rerank 请求: model={}, query={}, docCount={}", modelName, query, documents.size());

            String responseJson = webClient.post()
                    // 3. 修正 URI 路径
                    // 完整 URL 应为: {baseUrl}/text-rerank/text-rerank
                    // 如果你的 baseUrl 已经包含到了 .../rerank，这里填 /text-rerank/text-rerank
                    .uri(apiPath)
                    .bodyValue(requestBody) // 直接传 Map，WebClient 会自动序列化为 JSON
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError(), response ->
                            response.bodyToMono(String.class).flatMap(body -> {
                                log.error("❌ Rerank API 4xx 错误详情: {}", body);
                                return Mono.error(new RuntimeException("Rerank 请求参数错误: " + body));
                            })
                    )
                    .onStatus(status -> status.is5xxServerError(), response ->
                            response.bodyToMono(String.class).flatMap(body -> {
                                log.error("❌ Rerank API 5xx 错误详情: {}", body);
                                return Mono.error(new RuntimeException("Rerank 服务内部错误: " + body));
                            })
                    )
                    .bodyToMono(String.class)
                    .block(); // 阻塞调用

            return parseResponse(responseJson, documents);

        } catch (Exception e) {
            log.error("❌ 阿里云 Rerank 调用异常", e);
            // 降级策略：返回原顺序，分数设为 0
            List<RerankResult> fallback = new ArrayList<>();
            for (int i = 0; i < documents.size(); i++) {
                fallback.add(new RerankResult(documents.get(i), 0.0, i));
            }
            return fallback;
        }
    }

    private List<RerankResult> parseResponse(String json, List<String> originalDocs) throws Exception {
        JsonNode root = objectMapper.readTree(json);

        // 阿里云返回结构通常是: { "output": { "results": [ { "index": ..., "relevance_score": ..., "document": {...} } ] } }
        JsonNode outputNode = root.path("output");
        JsonNode resultsNode = outputNode.path("results");

        List<RerankResult> sortedList = new ArrayList<>();

        if (resultsNode.isArray()) {
            for (JsonNode item : resultsNode) {
                int index = item.path("index").asInt();
                double score = item.path("relevance_score").asDouble();

                // 尝试从返回结果中获取文本 (如果 return_documents=true)
                String text = item.path("document").path("text").asText();

                // 如果没返回文本，则从原列表获取（防止索引越界）
                if (text == null || text.isEmpty()) {
                    if (index >= 0 && index < originalDocs.size()) {
                        text = originalDocs.get(index);
                    } else {
                        text = "[内容丢失]";
                    }
                }

                sortedList.add(new RerankResult(text, score, index));
            }
        } else {
            log.warn("⚠️ Rerank 返回结果格式异常，未找到 results 数组");
        }

        // 实际上 API 返回的已经是按分数降序排列的，这里再次确认排序
        sortedList.sort((a, b) -> Double.compare(b.score, a.score));

        return sortedList;
    }
}
