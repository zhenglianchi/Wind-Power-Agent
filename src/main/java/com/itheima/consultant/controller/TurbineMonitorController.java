package com.itheima.consultant.controller;


import com.itheima.consultant.dto.TurbineQueryDTO;
import com.itheima.consultant.pojo.TurbineMonitorData;
import com.itheima.consultant.service.TurbineMonitorDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/turbine")
@CrossOrigin // 允许跨域
public class TurbineMonitorController {

    @Autowired
    private TurbineMonitorDataService turbineService;

    @Value("${rag.turbine.fault-limit:100}")
    private int faultLimit;

    /**
     * 通用查询接口
     * GET /api/turbine/query?wfName=东海&statusCode=RUNNING&limit=20
     */
    @GetMapping("/query")
    public List<TurbineMonitorData> queryData(TurbineQueryDTO queryDTO) {
        return turbineService.queryTurbineData(queryDTO);
    }

    /**
     * 根据状态和风场查询的专用接口
     * GET /api/turbine/status?statusCode=ERROR_204&wfCode=WF-001
     */
    @GetMapping("/status")
    public List<TurbineMonitorData> queryByStatus(
            @RequestParam String statusCode,
            @RequestParam(required = false) String wfCode) {

        TurbineQueryDTO dto = new TurbineQueryDTO();
        dto.setStatusCode(statusCode);
        dto.setWfCode(wfCode);
        dto.setLimit(faultLimit);

        return turbineService.queryTurbineData(dto);
    }
}
