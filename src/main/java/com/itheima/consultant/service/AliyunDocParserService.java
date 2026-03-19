package com.itheima.consultant.service;

import com.aliyun.docmind_api20220711.Client;
import com.aliyun.docmind_api20220711.models.*;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class AliyunDocParserService {

    @Value("${aliyun.docmind.access-key-id:}")
    private String accessKeyId;

    @Value("${aliyun.docmind.access-key-secret:}")
    private String accessKeySecret;

    @Value("${aliyun.docmind.enabled:true}")
    private boolean enabled;

    @Value("${aliyun.docmind.timeout-seconds:300}")
    private int timeoutSeconds;

    @Value("${aliyun.docmind.poll-interval-seconds:5}")
    private int pollIntervalSeconds;

    private Client client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() throws Exception {
        if (enabled && accessKeyId != null && !accessKeyId.isEmpty()) {
            Config config = new Config()
                    .setAccessKeyId(accessKeyId)
                    .setAccessKeySecret(accessKeySecret);
            config.endpoint = "docmind-api.cn-hangzhou.aliyuncs.com";
            client = new Client(config);
            log.info("✅ 阿里云文档智能服务初始化成功");
        } else {
            log.warn("⚠️ 阿里云文档智能服务未配置或未启用");
        }
    }

    public boolean isEnabled() {
        return enabled && client != null;
    }

    public DocParseResult parseDocument(Path filePath) {
        if (!isEnabled()) {
            log.warn("阿里云文档智能服务未启用");
            return null;
        }

        try {
            log.info("📄 [阿里云文档解析] 开始解析: {}", filePath.getFileName());

            String jobId = submitJob(filePath);
            if (jobId == null) {
                log.error("❌ 提交任务失败");
                return null;
            }

            log.info("⏳ [阿里云文档解析] 任务已提交，JobId: {}", jobId);

            if (!waitForCompletion(jobId)) {
                log.error("❌ 任务处理超时或失败");
                return null;
            }

            DocParseResult result = getResult(jobId);
            if (result != null) {
                log.info("✅ [阿里云文档解析] 解析完成，共 {} 个段落，{} 个大纲项",
                        result.paragraphs.size(), result.outline.size());
            }

            return result;

        } catch (Exception e) {
            log.error("❌ [阿里云文档解析] 解析失败", e);
            return null;
        }
    }

    private String submitJob(Path filePath) {
        try {
            File file = filePath.toFile();

            try (FileInputStream fileInputStream = new FileInputStream(file)) {
                SubmitDocParserJobAdvanceRequest request = new SubmitDocParserJobAdvanceRequest();
                request.fileUrlObject = fileInputStream;
                request.fileName = file.getName();

                RuntimeOptions runtime = new RuntimeOptions();

                SubmitDocParserJobResponse response = client.submitDocParserJobAdvance(request, runtime);

                if (response.getBody() != null && response.getBody().getData() != null) {
                    return response.getBody().getData().getId();
                }

                return null;
            }
        } catch (Exception e) {
            log.error("提交文档解析任务失败", e);
            return null;
        }
    }

    private boolean waitForCompletion(String jobId) throws InterruptedException {
        int maxRetries = timeoutSeconds / pollIntervalSeconds;

        for (int i = 0; i < maxRetries; i++) {
            Thread.sleep(pollIntervalSeconds * 1000L);

            QueryDocParserStatusRequest request = new QueryDocParserStatusRequest();
            request.setId(jobId);

            try {
                QueryDocParserStatusResponse response = client.queryDocParserStatus(request);

                if (response.getBody() != null && response.getBody().getData() != null) {
                    String status = response.getBody().getData().getStatus();
                    log.debug("任务状态: {}", status);

                    if ("SUCCEEDED".equals(status)) {
                        return true;
                    } else if ("FAILED".equals(status)) {
                        log.error("任务处理失败，状态: {}", status);
                        return false;
                    }
                }
            } catch (Exception e) {
                log.warn("查询任务状态异常: {}", e.getMessage());
            }

            log.info("⏳ 等待任务完成... ({}/{})", i + 1, maxRetries);
        }

        return false;
    }

    private DocParseResult getResult(String jobId) {
        try {
            GetDocParserResultRequest request = new GetDocParserResultRequest();
            request.setId(jobId);

            GetDocParserResultResponse response = client.getDocParserResult(request);

            if (response.getBody() != null && response.getBody().getData() != null) {
                return parseResult(response.getBody().getData());
            }

            return null;
        } catch (Exception e) {
            log.error("获取解析结果失败", e);
            return null;
        }
    }

    private DocParseResult parseResult(Object data) {
        try {
            String jsonStr = objectMapper.writeValueAsString(data);
            JsonNode root = objectMapper.readTree(jsonStr);

            DocParseResult result = new DocParseResult();
            result.rawJson = jsonStr;

            JsonNode layoutNode = root.path("layout");
            if (layoutNode.isArray()) {
                for (JsonNode node : layoutNode) {
                    Paragraph para = parseParagraph(node);
                    if (para != null && para.text != null && !para.text.trim().isEmpty()) {
                        result.paragraphs.add(para);
                    }
                }
            }

            JsonNode outlineNode = root.path("outline");
            if (outlineNode.isArray()) {
                for (JsonNode node : outlineNode) {
                    OutlineItem item = parseOutlineItem(node);
                    if (item != null) {
                        result.outline.add(item);
                    }
                }
            }

            JsonNode markdownNode = root.path("content");
            if (!markdownNode.isMissingNode()) {
                result.markdown = markdownNode.asText();
            }

            return result;
        } catch (Exception e) {
            log.error("解析结果转换失败", e);
            return null;
        }
    }

    private Paragraph parseParagraph(JsonNode node) {
        try {
            Paragraph para = new Paragraph();
            para.text = node.path("text").asText("");
            para.type = node.path("type").asText("text");
            para.pageIndex = node.path("pageIdx").asInt(0);

            JsonNode posNode = node.path("pos");
            if (posNode.isArray() && posNode.size() >= 4) {
                para.x = posNode.get(0).asDouble();
                para.y = posNode.get(1).asDouble();
                para.width = posNode.get(2).asDouble() - para.x;
                para.height = posNode.get(3).asDouble() - para.y;
            }

            String type = para.type.toLowerCase();
            if (type.contains("title") || type.contains("header")) {
                para.isTitle = true;
                para.level = detectTitleLevel(para.text);
            } else if (type.contains("table")) {
                para.isTable = true;
            } else if (type.contains("image") || type.contains("figure")) {
                para.isImage = true;
            }

            JsonNode llmResultNode = node.path("llmResult");
            if (!llmResultNode.isMissingNode()) {
                para.llmResult = llmResultNode.asText();
            }

            return para;
        } catch (Exception e) {
            return null;
        }
    }

    private int detectTitleLevel(String text) {
        if (text == null || text.isEmpty()) return 3;

        if (text.matches("^第[一二三四五六七八九十]+[章节篇部].*")) return 1;
        if (text.matches("^第\\d+[章节篇部].*")) return 1;
        if (text.matches("^\\d+\\.\\d+\\.\\d+.*")) return 3;
        if (text.matches("^\\d+\\.\\d+.*")) return 2;
        if (text.matches("^[一二三四五六七八九十]+[、.．].*")) return 2;
        if (text.matches("^\\d+[、.．].*")) return 2;

        return 3;
    }

    private OutlineItem parseOutlineItem(JsonNode node) {
        try {
            OutlineItem item = new OutlineItem();
            item.text = node.path("text").asText("");
            item.level = node.path("level").asInt(1);
            item.pageIndex = node.path("pageIdx").asInt(0);
            return item;
        } catch (Exception e) {
            return null;
        }
    }

    public static class DocParseResult {
        public String rawJson;
        public String markdown;
        public List<Paragraph> paragraphs = new ArrayList<>();
        public List<OutlineItem> outline = new ArrayList<>();
    }

    public static class Paragraph {
        public String text;
        public String type;
        public int pageIndex;
        public double x, y, width, height;
        public boolean isTitle;
        public boolean isTable;
        public boolean isImage;
        public int level;
        public String llmResult;
    }

    public static class OutlineItem {
        public String text;
        public int level;
        public int pageIndex;
    }
}
