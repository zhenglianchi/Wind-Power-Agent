package com.itheima.consultant.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField; // 引入这个
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("turbine_monitor_data")
public class TurbineMonitorData implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String wfName;   // 自动映射为 wf_name
    private String wfCode;   // 自动映射为 wf_code
    private String turbineCode;
    private Integer channelNo;

    private String statusCode;
    private Long cycleCount;
    private LocalDateTime recordTime;

    // 👇 关键修复：显式指定数据库列名带下划线
    @TableField("feature_1")
    private Double feature1;

    @TableField("feature_2")
    private Double feature2;

    @TableField("feature_3")
    private Double feature3;

    private LocalDateTime createTime;
}
