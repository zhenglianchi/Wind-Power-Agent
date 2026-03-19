package com.itheima.consultant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import com.itheima.consultant.dto.TurbineQueryDTO;
import com.itheima.consultant.mapper.TurbineMonitorDataMapper;
import com.itheima.consultant.pojo.TurbineMonitorData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class TurbineMonitorDataService {

    @Autowired
    private TurbineMonitorDataMapper turbineMapper;

    @Value("${rag.turbine.default-limit:50}")
    private int defaultLimit;

    @Value("${rag.turbine.fault-limit:100}")
    private int faultLimit;

    /**
     * 核心动态查询方法
     * 支持：风场名称、风场编号、风机编号、状态代码、时间范围的多条件组合查询
     */
    public List<TurbineMonitorData> queryTurbineData(TurbineQueryDTO queryDTO) {
        LambdaQueryWrapper<TurbineMonitorData> wrapper = new LambdaQueryWrapper<>();

        // 1. 风场名称 (模糊查询)
        if (StringUtils.hasText(queryDTO.getWfName())) {
            wrapper.like(TurbineMonitorData::getWfName, queryDTO.getWfName());
        }

        // 2. 风场编号 (精确查询)
        if (StringUtils.hasText(queryDTO.getWfCode())) {
            wrapper.eq(TurbineMonitorData::getWfCode, queryDTO.getWfCode());
        }

        // 3. 风机编号 (精确查询)
        if (StringUtils.hasText(queryDTO.getTurbineCode())) {
            wrapper.eq(TurbineMonitorData::getTurbineCode, queryDTO.getTurbineCode());
        }

        // 4. 状态代码 (精确查询 - 核心需求：根据状态查)
        if (StringUtils.hasText(queryDTO.getStatusCode())) {
            wrapper.eq(TurbineMonitorData::getStatusCode, queryDTO.getStatusCode());
        }

        // 5. 时间范围查询
        if (queryDTO.getStartTime() != null) {
            wrapper.ge(TurbineMonitorData::getRecordTime, queryDTO.getStartTime());
        }
        if (queryDTO.getEndTime() != null) {
            wrapper.le(TurbineMonitorData::getRecordTime, queryDTO.getEndTime());
        }

        // 6. 默认按时间倒序，并限制条数 (保护 Context Window)
        wrapper.orderByDesc(TurbineMonitorData::getRecordTime);

        // 如果 limit 为空，默认查配置的条数，防止大模型把数据库查爆
        int limit = (queryDTO.getLimit() != null && queryDTO.getLimit() > 0)
                ? queryDTO.getLimit() : defaultLimit;

        // 执行查询 (使用 page 方法来方便控制 limit，虽然这里只取 records)
        Page<TurbineMonitorData> page = new Page<>(1, limit);
        return turbineMapper.selectPage(page, wrapper).getRecords();
    }

    /**
     * 快捷方法：专门根据状态和风场查询 (Agent 可能会直接调用这个语义更明确的方法)
     */
    public List<TurbineMonitorData> queryByStatusAndWindFarm(String statusCode, String wfCode) {
        TurbineQueryDTO dto = new TurbineQueryDTO();
        dto.setStatusCode(statusCode);
        dto.setWfCode(wfCode);
        dto.setLimit(faultLimit); // 这种特定查询可以多给一点
        return queryTurbineData(dto);
    }
}
