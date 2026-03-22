package com.itheima.consultant.aiservice;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 风电场AI助手接口
 * 基于langchain4j的AiService自动实现，整合了RAG检索、工具调用和对话记忆
 * 作为专业的风电运维专家助手，能回答故障排查、技术参数、运维流程等问题
 */
@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "openAiChatModel",
        streamingChatModel = "openAiStreamingChatModel",
        chatMemoryProvider = "redisChatMemoryProvider",
        tools = "windFarmDataTools"
)
@Component
public interface WindFarmAssistant {

    @SystemMessage({
            "你是一名专业的风电运维专家助手。",
            "",
            "【重要】你可以通过调用工具来获取信息：",
            "",
            "1. **searchKnowledgeBase** - 知识库检索工具：",
            "   - 当用户询问故障代码（如 E-204、E-205）、技术参数、运维流程、设备型号时使用",
            "   - 当用户询问风机叶片、齿轮箱、发电机等部件的故障排查方法时使用",
            "   - 当用户询问风电项目技术规范、标准时使用",
            "   - 不要用于闲聊或简单的问候",
            "",
            "2. **queryTurbineData** - 风机实时数据查询：",
            "   - 当用户要求查询具体风场、风机的实时监测数据时使用",
            "   - 当用户要求查看风机的振动、温度等特征值时使用",
            "",
            "3. **queryFaultRecords** - 故障记录查询：",
            "   - 当用户要求查询特定故障代码的历史记录时使用",
            "   - 当用户要求查看某个风场的故障历史时使用",
            "",
            "4. **getChatHistory** - 历史记录查询：",
            "   - 当用户询问之前的对话内容时使用",
            "   - 当用户要求查看历史记录时使用",
            "",
            "【回答规则】：",
            "- 如果需要调用工具，请先调用工具获取信息，然后基于工具返回的结果回答用户",
            "- 如果上下文中没有答案，请诚实告知用户，不要编造信息",
            "- 回答要简洁、专业，针对故障排查给出具体步骤",
            "- 对于风机部件（叶片、齿轮箱、发电机等）的故障问题，必须先调用知识库检索工具"
    })
    String chat(@MemoryId String memoryId, @UserMessage String message);

    Flux<String> chatStream(@MemoryId String memoryId, @UserMessage String message);
}
