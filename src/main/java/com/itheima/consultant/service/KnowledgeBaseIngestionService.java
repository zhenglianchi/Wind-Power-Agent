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

/**
 * 知识库摄入服务
 * 负责将PDF文档加载、分割、向量化并存储到向量数据库（Redis）
 * 是RAG系统的数据入口，负责构建知识库索引
 */
@Slf4j
@Service
public class KnowledgeBaseIngestionService {

    // 向量化模型，将文本片段转换为向量
    @Autowired
    private EmbeddingModel embeddingModel;

    // 向量存储，存储所有文档片段的向量和原文
    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    // 事件发布器，用于发布知识库更新事件（触发缓存清空等操作）
    @Autowired
    private ApplicationEventPublisher eventPublisher;

    // 阿里云文档解析服务，支持智能分割
    @Autowired
    private AliyunDocParserService aliyunDocParserService;

    // PDF文档所在目录路径
    @Value("${rag.pdf-path:src/main/resources/content}")
    private String pdfPath;

    // 是否在应用启动时自动初始化知识库
    @Value("${rag.auto-init:true}")
    private boolean autoInit;

    // 递归分块策略：每个块的最大字符数
    @Value("${rag.chunk-size:800}")
    private int chunkSize;

    // 分块重叠字符数，保持上下文连续性
    @Value("${rag.chunk-overlap:200}")
    private int chunkOverlap;

    // 文档分割策略：ALIYUN_SMART / TITLE / RECURSIVE
    @Value("${rag.splitter.strategy:ALIYUN_SMART}")
    private String splitterStrategy;

    // 标题分割/智能分割：每个区块的最大字符数
    @Value("${rag.splitter.title.max-section-size:2000}")
    private int titleMaxSectionSize;

    // 标题分割/智能分割：是否对过大区块进行二次分割
    @Value("${rag.splitter.title.enable-sub-split:true}")
    private boolean titleEnableSubSplit;

    /**
     * 服务启动后自动初始化知识库
     * 如果auto-init为true，则在应用启动时自动加载PDF目录中的文档
     */
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

    /**
     * 批量摄入PDF文档到知识库
     * 加载目录下所有PDF文件，解析后分割向量化存储
     * @throws IOException 文件读取异常
     */
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

    /**
     * 批量摄入PDF文档到知识库，带进度回调
     * 用于前端触发知识库重建时显示进度
     * @param progressCallback 进度回调，参数为(当前处理数, 总数)
     * @throws IOException 文件读取异常
     */
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

    /**
     * 根据配置创建文档分割器
     * 支持三种分割策略：
     * 1. ALIYUN_SMART - 阿里云智能分割，基于文档结构语义分块
     * 2. TITLE - 基于标题分割，按标题层次结构分块
     * 3. RECURSIVE - 递归字符分割，基于固定大小重叠分割
     * @return 文档分割器实例
     */
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
