package com.itheima.consultant.tools;

import com.itheima.consultant.dto.TurbineQueryDTO;
import com.itheima.consultant.pojo.MemoryIdContext;
import com.itheima.consultant.pojo.TurbineMonitorData;
import com.itheima.consultant.repository.RedisChatMemoryProvider;
import com.itheima.consultant.service.EnhancedRAGService;
import com.itheima.consultant.service.TurbineMonitorDataService;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.rag.content.Content;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class WindFarmDataTools {

    @Autowired
    private RedisChatMemoryProvider chatMemoryProvider;

    @Autowired
    private TurbineMonitorDataService dataService;

    @Autowired
    private EnhancedRAGService enhancedRAGService;

    @org.springframework.beans.factory.annotation.Value("${rag.turbine.default-limit:50}")
    private int defaultLimit;

    @org.springframework.beans.factory.annotation.Value("${rag.turbine.fault-limit:100}")
    private int faultLimit;

    /**
     * 工具 1：风电知识库检索（RAG）- 增强版
     * 集成查询改写和 HyDE 功能
     */
    @Tool("检索风电运维知识库。当用户询问以下内容时必须调用此工具：" +
            "1. 故障代码（如 E-204、E-205 等）的含义和处理方法；" +
            "2. 风机部件（叶片、齿轮箱、发电机、变桨系统等）的故障排查；" +
            "3. 技术参数、运维流程、操作规范；" +
            "4. 风电行业标准、技术规范；" +
            "5. 设备型号、技术规格等专业知识。" +
            "注意：不要用于闲聊、问候或简单的日常对话。")
    public String searchKnowledgeBase(
            @P("用户的搜索查询语句，应包含关键词，如故障代码、部件名称、技术术语等") String query
    ) {
        log.info("🔍 [RAG Tool] 触发知识库检索，原始查询：{}", query);

        try {
            EnhancedRAGService.EnhancedRAGResult result = enhancedRAGService.retrieveWithDetails(query);

            if (result.contents() == null || result.contents().isEmpty()) {
                log.warn("⚠️ [RAG Tool] 知识库未检索到相关内容");
                return "未在知识库中找到与 '" + query + "' 相关的信息。请尝试使用其他关键词搜索，或联系技术支持。";
            }

            StringBuilder resultBuilder = new StringBuilder();
            resultBuilder.append("📚 从知识库检索到以下相关信息：\n\n");

            if (result.hasRewrite()) {
                resultBuilder.append("💡 [查询优化] 原始查询已改写为：").append(result.rewrittenQuery()).append("\n\n");
            }

            if (result.hasHyDE()) {
                resultBuilder.append("📄 [HyDE] 已生成假设文档辅助检索\n\n");
            }

            int count = 0;
            for (Content content : result.contents()) {
                if (count >= 5) break;
                String text = content.textSegment().text();
                resultBuilder.append("【片段 ").append(++count).append("】\n");
                resultBuilder.append(text).append("\n\n");
            }

            log.info("✅ [RAG Tool] 检索完成，返回 {} 条片段，耗时 {} ms", 
                    result.totalContents(), result.durationMs());

            return resultBuilder.toString();

        } catch (Exception e) {
            log.error("❌ [RAG Tool] 知识库检索执行失败", e);
            return "知识库检索服务暂时不可用，请稍后重试。";
        }
    }

    /**
     * 工具 2：风机实时监测数据查询
     */
    @Tool("查询风机运行监测数据。当用户需要查看风机的实时状态、振动数据、温度数据等监测信息时使用。" +
            "可以按风场名称、风场编号、风机编号、状态代码等条件筛选。")
    public String queryTurbineData(
            @P("风场名称") String wfName,
            @P("风场编号") String wfCode,
            @P("风机编号") String turbineCode,
            @P("状态代码") String statusCode,
            @P("限制返回条数，默认50") Integer limit
    ) {
        log.info("📊 [Data Tool] 查询风机监测数据 - 风场:{}, 风机:{}, 状态:{}", wfCode, turbineCode, statusCode);

        try {
            TurbineQueryDTO dto = new TurbineQueryDTO()
                    .setWfName(wfName)
                    .setWfCode(wfCode)
                    .setTurbineCode(turbineCode)
                    .setStatusCode(statusCode)
                    .setLimit(limit != null ? limit : defaultLimit);

            List<TurbineMonitorData> results = dataService.queryTurbineData(dto);

            if (results == null || results.isEmpty()) {
                return "未查询到符合条件的风机数据。请检查查询条件是否正确。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("查询到 ").append(results.size()).append(" 条监测记录：\n\n");
            sb.append("| 时间 | 风场 | 风机编号 | 状态 | 振动值 | 温度值 |\n");
            sb.append("|------|------|----------|------|--------|--------|\n");

            for (TurbineMonitorData d : results) {
                String time = d.getRecordTime() != null ? d.getRecordTime().toString() : "-";
                double f1 = d.getFeature1() != null ? d.getFeature1() : 0.0;
                double f2 = d.getFeature2() != null ? d.getFeature2() : 0.0;

                sb.append(String.format("| %s | %s | %s | %s | %.2f | %.1f |\n",
                        time,
                        d.getWfName() != null ? d.getWfName() : "-",
                        d.getTurbineCode() != null ? d.getTurbineCode() : "-",
                        d.getStatusCode() != null ? d.getStatusCode() : "-",
                        f1, f2));
            }
            return sb.toString();

        } catch (Exception e) {
            log.error("❌ [Data Tool] 查询执行出错", e);
            return "查询执行出错：" + e.getMessage();
        }
    }

    /**
     * 工具 3：故障记录查询
     */
    @Tool("查询故障记录。当用户需要查看特定故障代码的历史记录，或查看某个风场的故障历史时使用。")
    public String queryFaultRecords(
            @P("状态代码/故障代码，如 E-204") String statusCode,
            @P("风场编号，可选") String wfCode
    ) {
        log.info("🔧 [Fault Tool] 查询故障记录 - 状态码:{}, 风场:{}", statusCode, wfCode);

        try {
            if (statusCode == null || statusCode.trim().isEmpty()) {
                return "错误：必须提供状态代码（故障代码）。";
            }

            TurbineQueryDTO dto = new TurbineQueryDTO()
                    .setStatusCode(statusCode.trim())
                    .setWfCode(wfCode != null ? wfCode.trim() : null)
                    .setLimit(faultLimit);

            List<TurbineMonitorData> results = dataService.queryTurbineData(dto);

            if (results == null || results.isEmpty()) {
                String scope = (wfCode == null) ? "所有风场" : "风场 [" + wfCode + "]";
                return String.format("在 %s 中未找到状态为 [%s] 的故障记录。", scope, statusCode);
            }

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("共发现 %d 条 [%s] 故障记录：\n\n", results.size(), statusCode));

            for (TurbineMonitorData d : results) {
                sb.append(String.format("- [%s] 风机 %s 于 %s 故障 (振动值：%.2f)\n",
                        d.getWfName() != null ? d.getWfName() : "-",
                        d.getTurbineCode() != null ? d.getTurbineCode() : "-",
                        d.getRecordTime() != null ? d.getRecordTime() : "-",
                        d.getFeature1() != null ? d.getFeature1() : 0.0));
            }

            return sb.toString();

        } catch (Exception e) {
            log.error("❌ [Fault Tool] 故障查询执行出错", e);
            return "故障查询执行出错：" + e.getMessage();
        }
    }

    /**
     * 工具 4：聊天历史查询
     */
    @Tool("获取当前用户的聊天历史记录。当用户询问之前的对话内容、要求回顾历史、或需要参考之前的故障排查步骤时使用。")
    public String getChatHistory() {
        String userId = MemoryIdContext.get();

        if (userId == null) {
            return "错误：无法获取当前用户身份。";
        }

        try {
            List<ChatMessage> history = chatMemoryProvider.getFullHistory(userId);

            if (history.isEmpty()) {
                return "当前用户暂无历史聊天记录。";
            }

            return ChatMessageSerializer.messagesToJson(history);
        } catch (Exception e) {
            log.error("❌ [History Tool] 获取历史记录失败", e);
            return "获取历史记录失败: " + e.getMessage();
        }
    }
}
