# 风电智能运维问答系统

基于 LangChain4j + Spring Boot + Redis 构建的风电领域智能问答系统，集成 RAG 检索增强、多级缓存、消息队列、降级保护等企业级特性。

## 项目架构

```
Wind-Power-Q-A/
├── src/main/java/com/itheima/consultant/
│   ├── aiservice/           # AI服务层
│   │   └── WindFarmAssistant.java
│   ├── config/              # 配置类
│   │   ├── CommonConfig.java
│   │   ├── DegradationConfig.java
│   │   ├── RabbitMQConfig.java
│   │   └── RedisConfig.java
│   ├── controller/          # 控制器层
│   │   ├── ChatController.java
│   │   ├── KnowledgeBaseController.java
│   │   └── TurbineMonitorController.java
│   ├── dto/                 # 数据传输对象
│   │   ├── ChatMessage.java
│   │   ├── ChatResponse.java
│   │   ├── KnowledgeRebuildTask.java
│   │   ├── KnowledgeRebuildStatus.java
│   │   ├── TurbineQueryDTO.java
│   │   └── TitleSection.java
│   ├── service/             # 服务层
│   │   ├── AliyunDocParserService.java
│   │   ├── AliyunRerankService.java
│   │   ├── ChatMessageConsumer.java
│   │   ├── ChatMessageProducer.java
│   │   ├── DegradationService.java
│   │   ├── EnhancedRAGService.java
│   │   ├── HyDEService.java
│   │   ├── KnowledgeBaseIngestionService.java
│   │   ├── KnowledgeRebuildConsumer.java
│   │   ├── KnowledgeRebuildProducer.java
│   │   ├── QueryRewriteService.java
│   │   ├── RagCacheService.java
│   │   ├── SemanticCacheService.java
│   │   └── TurbineMonitorDataService.java
│   ├── retriever/           # 检索器
│   │   └── HybridRerankRetriever.java
│   ├── splitter/            # 文档分割器
│   │   ├── AliyunSmartSplitter.java
│   │   └── TitleBasedSplitter.java
│   ├── tools/               # Agent工具
│   │   └── WindFarmDataTools.java
│   └── repository/          # 存储层
│       └── RedisChatMemoryProvider.java
└── src/main/resources/
    ├── application.yml
    ├── content/             # PDF知识库文档
    └── system.txt           # 系统提示词
```

## 核心功能模块

### 1. RAG 检索增强生成

#### 1.1 混合检索 (Hybrid Retrieval)
- **向量检索**: 使用阿里云 text-embedding-v3 模型进行语义向量检索
- **关键词检索**: 使用 RediSearch 进行全文检索，支持中文分词
- **RRF 融合**: 使用 Reciprocal Rank Fusion 算法合并两种检索结果

```java
// HybridRerankRetriever.java
private List<ScoredDocument> mergeWithRRF(List<ScoredDocument> vectorDocs, List<ScoredDocument> keywordDocs) {
    // RRF 公式: score = 1/(k + rank)
    double rrfScore = 1.0 / (rrfK + i + 1);
    rrfScoreMap.merge(docId, rrfScore, Double::sum);
}
```

#### 1.2 重排序 (Rerank)
- 使用阿里云 GTE-Rerank-v2 模型对检索结果进行重排序
- 过滤低相关性结果（阈值可配置）

#### 1.3 查询增强
- **查询改写 (Query Rewrite)**: 将用户查询扩展为多个相关查询
- **HyDE (Hypothetical Document Embeddings)**: 生成假设文档辅助检索

```java
// EnhancedRAGService.java
public List<Content> retrieve(String originalQuery) {
    // 1. 查询改写
    if (useQueryRewrite) {
        QueryRewriteService.RewriteResult rewriteResult = queryRewriteService.process(originalQuery);
        searchQueries.addAll(rewriteResult.expandedQueries());
    }
    
    // 2. HyDE 生成假设文档
    if (useHyde) {
        HyDEService.HyDEResult hydeResult = hydeService.process(originalQuery);
        searchQueries.addAll(hydeResult.hypotheticalDocuments());
    }
    
    // 3. 多查询检索并合并
    return mergeResults(searchQueries);
}
```

### 2. 智能文档解析

#### 2.1 阿里云文档智能 API
- 支持深度学习解析 PDF 文档
- 自动识别标题层级、表格、图片
- 表格内容智能提取

```java
// AliyunDocParserService.java
public DocParseResult parseDocument(Path filePath) {
    // 1. 提交解析任务
    String jobId = submitJob(filePath);
    
    // 2. 轮询等待完成
    waitForCompletion(jobId);
    
    // 3. 获取解析结果
    return getResult(jobId);
}
```

#### 2.2 文档分割策略
- **ALIYUN_SMART**: 使用阿里云解析结果按标题层级分割
- **TITLE**: 本地标题识别分割
- **RECURSIVE**: 递归字符分割（兜底方案）

```java
// KnowledgeBaseIngestionService.java
private DocumentSplitter createSplitter() {
    return switch (strategy) {
        case "ALIYUN_SMART" -> new AliyunSmartSplitter(...);
        case "TITLE" -> new TitleBasedSplitter(...);
        case "RECURSIVE" -> DocumentSplitters.recursive(chunkSize, chunkOverlap);
    };
}
```

### 3. 多级缓存系统

#### 3.1 三级缓存架构
- **L1 缓存**: Caffeine 本地缓存（毫秒级响应，精确匹配）
- **L2 缓存**: Redis 分布式缓存（跨实例共享，精确匹配）
- **L3 缓存**: 语义缓存（RediSearch Vector Search，相似问题匹配）

**语义缓存工作原理**：当精确缓存不命中时，使用 KNN 向量搜索查找语义相似的已缓存问题，如果相似度超过阈值则直接返回缓存答案，大大提高缓存命中率。

```java
// RagCacheService.java
public String get(String question) {
    // 1. 先查 L1 Caffeine（精确匹配）
    String cached = localCache.getIfPresent(cacheKey);
    if (cached != null) return cached;

    // 2. 再查 L2 Redis（精确匹配）
    cached = redisTemplate.opsForValue().get(redisKey);
    if (cached != null) {
        localCache.put(cacheKey, cached); // 回填 L1
        return cached;
    }

    // 3. 尝试语义缓存匹配（精确不命中时，查找相似问题）
    if (semanticCacheService.isEnabled()) {
        String semanticAnswer = semanticCacheService.findSimilarAnswer(question);
        if (semanticAnswer != null) {
            localCache.put(cacheKey, semanticAnswer); // 回填 L1
            return semanticAnswer;
        }
    }

    return null;
}
```

#### 3.2 语义缓存核心特性

基于 RediSearch Vector Search 实现，自动创建向量索引，支持相似问题命中缓存：

| 特性 | 说明 |
|-----|------|
| **自动索引创建** | 启动时自动检查并创建索引，无需手动操作 |
| **余弦相似度** | 使用余弦相似度计算问题语义相似度 |
| **可配置阈值** | 通过 `min-similarity` 控制匹配严格度 |
| **存储结构** | 每个问题存储为 Redis HASH，包含向量、原始问题、缓存 key |
| **一致性维护** | 清除缓存时同步清理语义索引 |

```java
// SemanticCacheService.java
public String findSimilarAnswer(String question) {
    // 1. 计算问题嵌入向量
    Embedding embedding = embeddingModel.embed(question).content();
    float[] vector = embedding.vector();

    // 2. KNN 搜索最相似的 Top-N 问题
    List<SearchResult> results = searchKnn(vector);

    // 3. 遍历结果找到第一个超过相似度阈值的
    for (SearchResult result : results) {
        // RediSearch 返回余弦距离 (0~2)，转换为相似度: similarity = 1 - distance/2
        double similarity = 1.0 - (result.distance / 2.0);
        if (similarity >= minSimilarity) {
            // 返回对应缓存答案
            String cachedAnswer = redisTemplate.opsForValue().get(cacheKey);
            return cachedAnswer;
        }
    }
    return null;
}
```

#### 3.3 缓存防护机制

| 防护类型 | 问题描述 | 解决方案 |
|---------|---------|---------|
| **缓存穿透** | 查询不存在的数据绕过缓存 | 空值缓存（短 TTL） |
| **缓存击穿** | 热点 Key 过期瞬间大量请求 | 分布式锁 + 互斥更新 |
| **缓存雪崩** | 大量缓存同时过期 | 随机过期时间偏移 |

```java
// 缓存穿透防护
if (answer == null && penetrationEnabled) {
    putNullCache(cacheKey); // 缓存空值
}

// 缓存击穿防护
Boolean locked = redisTemplate.opsForValue()
    .setIfAbsent(lockKey, "1", lockExpireSeconds, TimeUnit.SECONDS);
if (locked) {
    answer = loader.get();
    put(question, answer);
}

// 缓存雪崩防护
int randomOffset = random.nextInt(randomRangeSeconds);
int expireSeconds = baseExpireSeconds + randomOffset;
```

### 4. RabbitMQ 消息队列

#### 4.1 异步处理架构
- **请求队列**: chat.queue.v2
- **响应队列**: chat.response.queue.v2
- **死信队列**: chat.dlq.v2（失败消息重试）

```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│   Client    │───▶│  Request    │───▶│  Consumer   │
│  (Producer) │    │   Queue     │    │  (Worker)   │
└─────────────┘    └─────────────┘    └──────┬──────┘
                                              │
                      ┌─────────────┐         │
                      │   Response  │◀────────┘
                      │   Queue     │
                      └──────┬──────┘
                             │
                      ┌──────▼──────┐
                      │   Client    │
                      │ (等待响应)   │
                      └─────────────┘
```

#### 4.2 限流配置

| 参数 | 默认值 | 说明 |
|-----|-------|------|
| `concurrency` | 5 | 最小并发消费者数 |
| `max-concurrency` | 20 | 最大并发消费者数 |
| `prefetch` | 2 | 每个消费者预取消息数 |
| `queue-max-length` | 10000 | 队列最大消息数 |

**最大并发处理能力 = max-concurrency × prefetch = 40 条消息/批次**

#### 4.3 死信队列重试
- 消息处理失败自动进入死信队列
- 最多重试 3 次
- 超过重试次数后丢弃

```java
// ChatMessageConsumer.java
@RabbitListener(queues = RabbitMQConfig.CHAT_DLQ)
public void handleDeadLetter(ChatMessage message) {
    if (message.getRetryCount() < 3) {
        message.setRetryCount(message.getRetryCount() + 1);
        rabbitTemplate.convertAndSend(CHAT_EXCHANGE, CHAT_ROUTING_KEY, message);
    }
}
```

#### 4.4 异步知识库重建

知识库重建是一个耗时的操作，使用 RabbitMQ 实现异步重建，确保用户在重建过程中可以继续进行对话等操作。

**架构设计**：

```
┌─────────────┐    ┌──────────────────┐    ┌─────────────────┐
│   Client    │───▶│  Rebuild Queue   │───▶│    Consumer     │
│ (触发重建)   │    │ (knowledge.queue)│    │  (后台处理重建)  │
└─────────────┘    └──────────────────┘    └────────┬────────┘
                                                    │
                     ┌──────────────────┐           │
                     │   Status Queue   │◀──────────┘
                     │  (进度更新消息)   │
                     └────────┬─────────┘
                              │
                     ┌────────▼─────────┐
                     │     Client       │
                     │  (轮询查询状态)   │
                     └──────────────────┘
```

**核心组件**：

| 组件 | 说明 |
|-----|------|
| `KnowledgeRebuildTask` | 重建任务 DTO，包含任务ID、类型等信息 |
| `KnowledgeRebuildStatus` | 重建状态 DTO，包含进度、状态、耗时等 |
| `KnowledgeRebuildProducer` | 生产者服务，提交任务和查询状态 |
| `KnowledgeRebuildConsumer` | 消费者服务，异步执行重建任务 |

**使用示例**：

```java
// 1. 提交异步重建任务
POST /api/knowledge/async/clear-and-rebuild
响应: {"success": true, "taskId": "xxx-xxx-xxx", "message": "任务已提交"}

// 2. 轮询查询状态
GET /api/knowledge/async/status/xxx-xxx-xxx
响应: {
    "success": true,
    "status": "RUNNING",
    "progress": 45,
    "currentStep": "正在处理文档 (5/10)",
    "totalDocuments": 10,
    "processedDocuments": 5
}

// 3. 任务完成
响应: {
    "success": true,
    "status": "COMPLETED",
    "progress": 100,
    "currentStep": "处理完成",
    "durationMs": 45000
}
```

**状态流转**：

```
PENDING → RUNNING → COMPLETED
                   ↘ FAILED
```

| 状态 | 说明 |
|-----|------|
| `PENDING` | 任务已提交，等待处理 |
| `RUNNING` | 任务正在执行中 |
| `COMPLETED` | 任务执行成功 |
| `FAILED` | 任务执行失败 |

**并发控制**：
- 同一时间只允许一个重建任务运行
- 提交新任务时会检查是否有正在运行的任务
- 状态信息存储在 Redis 中，支持分布式环境

### 5. 分级降级机制

#### 5.1 降级级别

| 级别 | 状态 | 行为 |
|-----|------|------|
| **NORMAL** | 正常 | 所有功能可用 |
| **DISABLE_CACHE** | 禁用缓存 | 直接查询后端 |
| **DISABLE_RAG** | 禁用RAG | 不检索知识库 |
| **DISABLE_TOOL** | 禁用工具 | 不调用Agent工具 |
| **EMERGENCY** | 紧急 | 返回预设消息 |

#### 5.2 自动降级触发
- 连续错误达到阈值（5次/分钟）自动升级降级级别
- 支持手动设置和重置

```java
// DegradationService.java
public void recordError(String component) {
    errorCounters.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();
    
    int errors = errorCounters.get(key).get();
    if (errors >= ERROR_THRESHOLD) {
        checkAndDegrade(component);
    }
}
```

### 6. Agent 工具集成

#### 6.1 知识库检索工具
```java
@Tool("检索风电运维知识库...")
public String searchKnowledgeBase(String query) {
    EnhancedRAGService.EnhancedRAGResult result = enhancedRAGService.retrieveWithDetails(query);
    return formatResult(result);
}
```

#### 6.2 风机数据查询工具
```java
@Tool("查询风机运行监测数据...")
public String queryTurbineData(String wfName, String wfCode, String turbineCode, String statusCode, Integer limit) {
    List<TurbineMonitorData> results = dataService.queryTurbineData(dto);
    return formatTable(results);
}
```

#### 6.3 故障记录查询工具
```java
@Tool("查询故障记录...")
public String queryFaultRecords(String statusCode, String wfCode) {
    // 查询特定故障代码的历史记录
}
```

#### 6.4 聊天历史工具
```java
@Tool("获取当前用户的聊天历史记录...")
public String getChatHistory() {
    List<ChatMessage> history = chatMemoryProvider.getFullHistory(userId);
    return ChatMessageSerializer.messagesToJson(history);
}
```

### 7. 流式响应 (WebFlux)

```java
// ChatController.java
@PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<String> chatStream(String memoryId, Map<String, String> payload) {
    return windFarmAssistant.chatStream(memoryId, message)
            .map(chunk -> "data: {\"content\": \"" + escapeJson(chunk) + "\"}\n\n")
            .concatWith(Flux.just("data: {\"done\": true}\n\n"));
}
```

## API 接口

### 聊天接口

| 方法 | 路径 | 说明 |
|-----|------|------|
| POST | `/api/chat` | 同步聊天（直接处理） |
| POST | `/api/chat/queue` | 异步聊天（消息队列） |
| POST | `/api/chat/stream` | 流式聊天（SSE） |
| POST | `/api/chat/safe` | 带防护的聊天 |

### 知识库管理

| 方法 | 路径 | 说明 |
|-----|------|------|
| POST | `/api/knowledge/rebuild` | 追加模式重建知识库（同步） |
| POST | `/api/knowledge/clear-and-rebuild` | 清空后重建知识库（同步） |
| POST | `/api/knowledge/async/rebuild` | 异步追加模式重建知识库 |
| POST | `/api/knowledge/async/clear-and-rebuild` | 异步清空后重建知识库 |
| GET | `/api/knowledge/async/status` | 获取当前重建任务状态 |
| GET | `/api/knowledge/async/status/{taskId}` | 获取指定任务状态 |

### 降级管理

| 方法 | 路径 | 说明 |
|-----|------|------|
| GET | `/api/degradation/status` | 获取降级状态 |
| POST | `/api/degradation/level?level=XXX` | 设置降级级别 |
| POST | `/api/degradation/reset` | 重置降级状态 |

### 缓存管理

| 方法 | 路径 | 说明 |
|-----|------|------|
| GET | `/api/cache/stats` | 获取缓存统计 |
| DELETE | `/api/cache` | 清除所有缓存 |

### 队列监控

| 方法 | 路径 | 说明 |
|-----|------|------|
| GET | `/api/queue/stats` | 获取队列待处理请求数 |

## 配置说明

### application.yml 核心配置

```yaml
langchain4j:
  open-ai:
    chat-model:
      base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
      model-name: qwen-plus
    embedding-model:
      model-name: text-embedding-v3

rag:
  splitter:
    strategy: ALIYUN_SMART  # 文档分割策略
  retrieval:
    top-k-recall: 20       # 召回数量
    min-score: 0.6         # 最小相似度
  cache:
    enabled: true
    l1:
      max-size: 50
      expire-minutes: 5
    l2:
      expire-minutes: 60
    semantic:
      enabled: true         # 是否启用语义缓存
      min-similarity: 0.85 # 最小相似度阈值（越高越严格）
      max-candidates: 10    # KNN搜索返回候选数
      index-name: "rag_cache_semantic_idx"
      vector-prefix: "rag:cache:vector:"
  degradation:
    level: NORMAL

rabbitmq:
  chat:
    concurrency: 5
    max-concurrency: 20
    prefetch: 2
    queue-max-length: 10000
```

## 技术栈

| 类别 | 技术 |
|-----|------|
| 框架 | Spring Boot 3.5, WebFlux |
| AI 框架 | LangChain4j 1.0.1 |
| LLM | 阿里云通义千问 (qwen-plus) |
| 向量模型 | text-embedding-v3 |
| 重排序模型 | GTE-Rerank-v2 |
| 向量数据库 | Redis (RediSearch) |
| 消息队列 | RabbitMQ |
| 缓存 | Caffeine + Redis + RediSearch 语义缓存 |
| 数据库 | MySQL + MyBatis-Plus |
| 监控 | Micrometer + Prometheus |

