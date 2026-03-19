package com.itheima.consultant.controller;

import com.itheima.consultant.service.KnowledgeBaseIngestionService;
import com.itheima.consultant.service.RagCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeBaseController {

    @Autowired
    private KnowledgeBaseIngestionService ingestionService;

    @Autowired
    private RagCacheService ragCacheService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @org.springframework.beans.factory.annotation.Value("${rag.index-name:wind-farm-knowledge}")
    private String indexName;

    /**
     * 手动触发知识库重建 (不清空，直接追加)
     */
    @PostMapping("/rebuild")
    public String rebuildKnowledgeBase() {
        log.info("👤 用户触发【追加模式】知识库重建...");
        try {
            long start = System.currentTimeMillis();
            ingestionService.ingestPdfDocuments();
            long end = System.currentTimeMillis();

            ragCacheService.evictAll();
            log.info("✅ 问答缓存已清除");

            return String.format("✅ 知识库追加成功！耗时：%d ms", (end - start));
        } catch (Exception e) {
            log.error("❌ 追加失败", e);
            return "❌ 追加失败：" + e.getMessage();
        }
    }

    /**
     * 【推荐】清空 Redis 索引后重建
     */
    @PostMapping("/clear-and-rebuild")
    public String clearAndRebuild() {
        log.info("🧹 用户触发【清空重建】知识库流程...");

        try {
            log.info("正在删除旧索引（含向量数据）：{}", indexName);

            Boolean dropResult = redisTemplate.execute((RedisConnection connection) -> {
                try {
                    Object result = connection.execute(
                            "FT.DROPINDEX",
                            indexName.getBytes(),
                            "DD".getBytes()
                    );
                    log.info("FT.DROPINDEX 执行结果: {}", result);
                    return true;
                } catch (Exception e) {
                    if (e.getMessage() != null && e.getMessage().contains("Unknown Index name")) {
                        log.warn("⚠️ 索引 [{}] 不存在，跳过删除步骤。", indexName);
                        return false;
                    }
                    throw e;
                }
            }, false);

            if (dropResult != null && dropResult) {
                log.info("✅ 旧索引 [{}] 及向量数据删除成功", indexName);
            }

            ragCacheService.evictAll();
            log.info("✅ 问答缓存已清除");

            log.info("🚀 开始重新加载 PDF 并构建新索引...");
            long start = System.currentTimeMillis();

            ingestionService.ingestPdfDocuments();

            long end = System.currentTimeMillis();

            return String.format("✅ 知识库清空重建成功！\n1. 索引 [%s] 及向量数据已删除\n2. 问答缓存已清除\n3. 总耗时：%d ms", indexName, (end - start));

        } catch (Exception e) {
            log.error("❌ 清空重建失败", e);
            return "❌ 清空重建失败：" + e.getMessage();
        }
    }
}
