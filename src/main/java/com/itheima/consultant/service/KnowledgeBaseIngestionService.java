package com.itheima.consultant.service;

import com.itheima.consultant.event.KnowledgeBaseUpdateEvent;
import com.itheima.consultant.splitter.AliyunSmartSplitter;
import com.itheima.consultant.splitter.TitleBasedSplitter;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.function.BiConsumer;

@Slf4j
@Service
public class KnowledgeBaseIngestionService {

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private AliyunDocParserService aliyunDocParserService;

    @Value("${rag.pdf-path:src/main/resources/content}")
    private String pdfPath;

    @Value("${rag.auto-init:true}")
    private boolean autoInit;

    @Value("${rag.chunk-size:800}")
    private int chunkSize;

    @Value("${rag.chunk-overlap:200}")
    private int chunkOverlap;

    @Value("${rag.splitter.strategy:ALIYUN_SMART}")
    private String splitterStrategy;

    @Value("${rag.splitter.title.max-section-size:2000}")
    private int titleMaxSectionSize;

    @Value("${rag.splitter.title.enable-sub-split:true}")
    private boolean titleEnableSubSplit;

    @PostConstruct
    public void initKnowledgeBase() {
        log.info("正在初始化风电知识库...");
        if (!autoInit) {
            log.info("⏭️ 自动初始化已关闭，跳过知识库构建。");
            return;
        }
        try {
            ingestPdfDocuments();
            log.info("✅ 风电知识库初始化完成！");
        } catch (Exception e) {
            log.error("❌ 知识库初始化失败", e);
        }
    }

    public void ingestPdfDocuments() throws IOException {
        Path directoryPath = Paths.get(pdfPath).toAbsolutePath();

        if (!Files.exists(directoryPath)) {
            log.warn("PDF 目录不存在：{}, 跳过知识库构建", directoryPath);
            return;
        }

        log.info("开始加载 PDF 文档，目录: {}", directoryPath);

        List<Document> documents = new ArrayList<>();
        DocumentParser parser = new ApachePdfBoxDocumentParser();

        try (Stream<Path> paths = Files.walk(directoryPath)) {
            paths.filter(Files::isRegularFile)
                 .filter(path -> path.toString().toLowerCase().endsWith(".pdf"))
                 .forEach(path -> {
                     try {
                         log.info("📄 加载文档: {}", path.getFileName());

                         Document document = FileSystemDocumentLoader.loadDocument(path, parser);

                         Metadata metadata = document.metadata();
                         metadata.put("source", path.getFileName().toString());
                         metadata.put("absolute_path", path.toString());

                         Document documentWithSource = Document.from(document.text(), metadata);
                         documents.add(documentWithSource);

                     } catch (Exception e) {
                         log.error("❌ 加载文档失败: {}", path, e);
                     }
                 });
        }

        if (documents.isEmpty()) {
            log.warn("未找到任何 PDF 文档");
            return;
        }

        log.info("共加载 {} 个文档", documents.size());

        DocumentSplitter splitter = createSplitter();

        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(splitter)
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build();

        log.info("正在向量化并存入 Redis...");
        ingestor.ingest(documents);
        log.info("存入成功！");

        eventPublisher.publishEvent(new KnowledgeBaseUpdateEvent(this, "INGEST", documents.size() + " documents"));
    }

    public void ingestPdfDocumentsWithCallback(BiConsumer<Integer, Integer> progressCallback) throws IOException {
        Path directoryPath = Paths.get(pdfPath).toAbsolutePath();

        if (!Files.exists(directoryPath)) {
            log.warn("PDF 目录不存在：{}, 跳过知识库构建", directoryPath);
            if (progressCallback != null) {
                progressCallback.accept(0, 0);
            }
            return;
        }

        log.info("开始加载 PDF 文档，目录: {}", directoryPath);

        List<Document> documents = new ArrayList<>();
        DocumentParser parser = new ApachePdfBoxDocumentParser();

        try (Stream<Path> paths = Files.walk(directoryPath)) {
            paths.filter(Files::isRegularFile)
                 .filter(path -> path.toString().toLowerCase().endsWith(".pdf"))
                 .forEach(path -> {
                     try {
                         log.info("📄 加载文档: {}", path.getFileName());

                         Document document = FileSystemDocumentLoader.loadDocument(path, parser);

                         Metadata metadata = document.metadata();
                         metadata.put("source", path.getFileName().toString());
                         metadata.put("absolute_path", path.toString());

                         Document documentWithSource = Document.from(document.text(), metadata);
                         documents.add(documentWithSource);

                     } catch (Exception e) {
                         log.error("❌ 加载文档失败: {}", path, e);
                     }
                 });
        }

        if (documents.isEmpty()) {
            log.warn("未找到任何 PDF 文档");
            if (progressCallback != null) {
                progressCallback.accept(0, 0);
            }
            return;
        }

        log.info("共加载 {} 个文档", documents.size());

        DocumentSplitter splitter = createSplitter();

        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(splitter)
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build();

        log.info("正在向量化并存入 Redis...");

        int total = documents.size();
        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            ingestor.ingest(List.of(doc));

            if (progressCallback != null) {
                progressCallback.accept(i + 1, total);
            }
        }

        log.info("存入成功！");

        eventPublisher.publishEvent(new KnowledgeBaseUpdateEvent(this, "INGEST", documents.size() + " documents"));
    }

    private DocumentSplitter createSplitter() {
        String strategy = splitterStrategy.toUpperCase();
        log.info("🔧 使用分割策略: {}", strategy);

        return switch (strategy) {
            case "ALIYUN_SMART" -> {
                log.info("🤖 阿里云智能分割配置 - 最大段落长度: {}, 启用子分割: {}", titleMaxSectionSize, titleEnableSubSplit);
                yield new AliyunSmartSplitter(aliyunDocParserService, titleMaxSectionSize, chunkOverlap, titleEnableSubSplit);
            }
            case "TITLE" -> {
                log.info("📄 标题分割配置 - 最大段落长度: {}, 启用子分割: {}", titleMaxSectionSize, titleEnableSubSplit);
                yield new TitleBasedSplitter(titleMaxSectionSize, chunkOverlap, titleEnableSubSplit);
            }
            case "RECURSIVE" -> {
                log.info("🔪 递归分割配置 - 块大小: {}, 重叠: {}", chunkSize, chunkOverlap);
                yield DocumentSplitters.recursive(chunkSize, chunkOverlap);
            }
            default -> {
                log.warn("⚠️ 未知的分割策略: {}, 使用默认 ALIYUN_SMART", strategy);
                yield new AliyunSmartSplitter(aliyunDocParserService, titleMaxSectionSize, chunkOverlap, titleEnableSubSplit);
            }
        };
    }
}
