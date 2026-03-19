package com.itheima.consultant.dto;

import lombok.Data;
import lombok.experimental.Accessors;
import java.time.LocalDateTime;

/**
 * 风机数据查询请求 DTO
 * 用于 Agent 工具调用的参数封装
 */
@Data
@Accessors(chain = true)
public class TurbineQueryDTO {

    /**
     * 风场名称 (模糊匹配)
     * 示例："东海风电场"
     */
    private String wfName;

    /**
     * 风场编号 (精确匹配)
     * 示例："WF-001"
     */
    private String wfCode;

    /**
     * 风机编号 (精确匹配)
     * 示例："CH-001-A"
     */
    private String turbineCode;

    /**
     * 状态代码 (精确匹配)
     * 示例："RUNNING", "ERROR_204"
     */
    private String statusCode;

    /**
     * 开始时间
     */
    private LocalDateTime startTime;

    /**
     * 结束时间
     */
    private LocalDateTime endTime;

    /**
     * 限制返回条数 (防止大模型一次性拉取太多数据导致 Context 溢出)
     */
    private Integer limit = 50;
}
