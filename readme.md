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

#### 3.1 两级缓存架构
- **L1 缓存**: Caffeine 本地缓存（毫秒级响应）
- **L2 缓存**: Redis 分布式缓存（跨实例共享）

```java
// RagCacheService.java
public String get(String question) {
    // 1. 先查 L1 Caffeine
    String cached = localCache.getIfPresent(cacheKey);
    if (cached != null) return cached;
    
    // 2. 再查 L2 Redis
    cached = redisTemplate.opsForValue().get(redisKey);
    if (cached != null) {
        localCache.put(cacheKey, cached); // 回填 L1
        return cached;
    }
    
    return null;
}
```

#### 3.2 缓存防护机制

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
| 缓存 | Caffeine + Redis |
| 数据库 | MySQL + MyBatis-Plus |
| 监控 | Micrometer + Prometheus |

---

## 高频面试题及解答

### 1. RAG 检索增强生成

**Q: 什么是 RAG？为什么需要 RAG？**

A: RAG（Retrieval-Augmented Generation）是一种将检索与生成相结合的技术。它通过检索外部知识库中的相关文档，将其作为上下文提供给 LLM，从而增强模型的回答能力。

**为什么需要 RAG：**
- **知识时效性**：LLM 训练数据有截止日期，无法获取最新知识
- **领域专业性**：企业私有知识（如风电运维手册）不在 LLM 训练数据中
- **减少幻觉**：基于检索到的真实文档回答，降低模型编造内容的风险
- **可解释性**：可以追溯答案来源，提供引用依据

**Q: 为什么使用混合检索而不是单一检索？**

A: 
- **向量检索**擅长语义理解，能找到意思相近但词汇不同的内容
- **关键词检索**擅长精确匹配，对专有名词、故障代码等效果更好
- **两者结合**可以互补，提高召回率和准确率
- 使用 RRF（Reciprocal Rank Fusion）算法合并结果，平衡两种检索的优势

**Q: HyDE 是什么？如何提升检索效果？**

A: HyDE（Hypothetical Document Embeddings）的核心思想是：让 LLM 先生成一个"假设性答案"，然后用这个假设答案去检索。

**原理**：
1. 用户提问："E-204故障怎么处理？"
2. LLM 生成假设文档："E-204故障通常由变桨系统异常引起，处理步骤包括..."
3. 用假设文档的向量去检索，更容易匹配到真实的技术文档

**优势**：假设文档包含了更多相关术语和上下文，检索效果往往优于直接用问题检索。

---

### 2. 缓存系统

**Q: 为什么要用多级缓存？Caffeine 和 Redis 各有什么特点？**

A:

| 特性 | Caffeine (L1) | Redis (L2) |
|-----|---------------|------------|
| **位置** | 应用内存 | 独立服务 |
| **速度** | 纳秒级 | 毫秒级 |
| **容量** | 受限于 JVM 堆内存 | 受限于服务器内存 |
| **共享** | 单实例 | 多实例共享 |
| **持久化** | 否 | 是 |

**多级缓存的优势**：
1. 热点数据在本地内存，响应极快
2. 非热点数据在 Redis，多实例共享
3. Redis 故障时，本地缓存仍可提供有限服务

**Q: 什么是缓存穿透、缓存击穿、缓存雪崩？如何解决？**

A:

| 问题 | 描述 | 解决方案 |
|-----|------|---------|
| **缓存穿透** | 恶意查询不存在的数据，绕过缓存直接打到数据库 | 1. 空值缓存（短TTL）<br>2. 布隆过滤器 |
| **缓存击穿** | 热点 Key 过期瞬间，大量请求同时穿透到数据库 | 1. 分布式锁互斥更新<br>2. 永不过期 + 异步更新 |
| **缓存雪崩** | 大量缓存同时过期，数据库压力骤增 | 1. 随机过期时间<br>2. 多级缓存<br>3. 熔断降级 |

**代码示例**：
```java
// 缓存穿透防护 - 空值缓存
if (answer == null) {
    redisTemplate.opsForValue().set(key, "NULL", 60, TimeUnit.SECONDS);
}

// 缓存击穿防护 - 分布式锁
Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", 10, TimeUnit.SECONDS);
if (locked) {
    answer = loadFromDB();
    cache.put(key, answer);
}

// 缓存雪崩防护 - 随机过期
int expire = baseExpire + random.nextInt(300);
```

---

### 3. 消息队列

**Q: 为什么使用 RabbitMQ 处理聊天请求？**

A:
1. **削峰填谷**：高并发时请求先入队，消费者按能力处理，避免系统崩溃
2. **解耦**：生产者和消费者独立运行，便于维护和扩展
3. **异步处理**：用户无需等待，提升体验
4. **可靠性**：消息持久化、确认机制、死信队列保证消息不丢失

**Q: 如何实现限流？参数如何配置？**

A: 通过消费者并发数和预取数量控制：

```yaml
rabbitmq:
  chat:
    concurrency: 5          # 最小消费者数
    max-concurrency: 20     # 最大消费者数
    prefetch: 2             # 每个消费者预取消息数
```

**最大并发处理数 = max-concurrency × prefetch = 40**

**Q: 死信队列是什么？如何使用？**

A: 死信队列（Dead Letter Queue）用于存储处理失败的消息。

**触发条件**：
1. 消息被拒绝且不重新入队
2. 消息过期
3. 队列满了

**本项目使用场景**：
- 消息处理失败自动进入 DLQ
- 监听 DLQ 进行重试（最多 3 次）
- 超过重试次数后记录日志并丢弃

```java
@RabbitListener(queues = CHAT_DLQ)
public void handleDeadLetter(ChatMessage message) {
    if (message.getRetryCount() < 3) {
        message.setRetryCount(message.getRetryCount() + 1);
        rabbitTemplate.convertAndSend(CHAT_EXCHANGE, CHAT_ROUTING_KEY, message);
    }
}
```

---

### 4. 高并发处理

**Q: 高并发场景下如何保证系统稳定性？**

A:

1. **限流**：RabbitMQ 消费者并发控制 + 队列最大长度
2. **缓存**：多级缓存减少数据库压力
3. **降级**：分级降级保护核心功能
4. **熔断**：连续错误触发降级
5. **异步**：消息队列解耦
6. **监控**：Micrometer 指标实时监控

**Q: 如何实现服务降级？**

A: 采用分级降级策略：

```
NORMAL → DISABLE_CACHE → DISABLE_RAG → DISABLE_TOOL → EMERGENCY
```

**触发条件**：
- 连续错误达到阈值（5次/分钟）
- 手动触发

**降级效果**：
- DISABLE_CACHE：跳过缓存，直接查询
- DISABLE_RAG：不检索知识库，直接回答
- EMERGENCY：返回预设消息，保护系统

---

### 5. 向量检索

**Q: 向量检索的原理是什么？**

A:
1. **Embedding**：将文本转换为高维向量（如 1536 维）
2. **存储**：将向量存入向量数据库（Redis）
3. **检索**：将查询文本转换为向量，计算与存储向量的相似度
4. **排序**：返回相似度最高的 Top-K 结果

**相似度计算**：通常使用余弦相似度或点积

**Q: 为什么需要 Rerank？**

A:
- 向量检索基于语义相似度，可能遗漏精确匹配
- Rerank 模型专门训练用于判断文档与查询的相关性
- Rerank 后的结果更准确，相关性更高

**流程**：向量召回 Top-20 → Rerank 重排序 → 返回 Top-5

---

### 6. 系统设计

**Q: 如果 Redis 挂了怎么办？**

A:
1. **本地缓存兜底**：Caffeine 仍可提供有限服务
2. **降级机制**：自动切换到 DISABLE_CACHE 模式
3. **熔断保护**：连续错误触发 EMERGENCY 模式
4. **监控告警**：Micrometer 指标 + Prometheus 告警

**Q: 如何保证消息不丢失？**

A:
1. **生产者确认**：RabbitMQ Publisher Confirm
2. **消息持久化**：队列和消息都设置为持久化
3. **消费者确认**：手动 ACK 或自动 ACK
4. **死信队列**：处理失败的消息进入 DLQ 重试

**Q: 如何扩展到分布式部署？**

A:
1. **无状态服务**：ChatController 无状态，可水平扩展
2. **共享存储**：Redis 作为共享缓存和向量存储
3. **消息队列**：RabbitMQ 多消费者实例
4. **负载均衡**：Nginx 或 Spring Cloud Gateway
5. **会话管理**：memoryId 关联 Redis 存储

---

### 7. 性能优化

**Q: 如何优化 RAG 检索性能？**

A:
1. **预计算向量**：文档入库时预计算并存储向量
2. **索引优化**：RediSearch 建立索引加速关键词检索
3. **批量处理**：批量 Embedding 减少网络开销
4. **缓存热查询**：热点问题缓存答案
5. **异步处理**：HyDE 和查询改写可异步执行

**Q: 如何监控和排查问题？**

A:
1. **日志**：详细日志记录每个步骤耗时
2. **指标**：Micrometer 暴露缓存命中率、检索耗时等
3. **链路追踪**：每个请求有唯一 messageId
4. **健康检查**：Actuator 端点监控服务状态

```bash
# 查看缓存统计
GET /api/cache/stats

# 查看降级状态
GET /api/degradation/status

# Prometheus 指标
GET /actuator/prometheus
```
