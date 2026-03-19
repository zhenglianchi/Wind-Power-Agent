package com.itheima.consultant.listener;

import com.itheima.consultant.event.KnowledgeBaseUpdateEvent;
import com.itheima.consultant.service.RagCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CacheInvalidationListener {

    private final RagCacheService ragCacheService;

    @Async
    @EventListener
    public void onKnowledgeBaseUpdate(KnowledgeBaseUpdateEvent event) {
        log.info("📚 [缓存一致性] 收到知识库更新事件 - 类型: {}, 文档: {}",
                event.getUpdateType(),
                event.getDocumentName() != null ? event.getDocumentName() : "全部");

        ragCacheService.evictAll();

        log.info("✅ [缓存一致性] 缓存已清除，确保知识库更新后数据一致");
    }
}
