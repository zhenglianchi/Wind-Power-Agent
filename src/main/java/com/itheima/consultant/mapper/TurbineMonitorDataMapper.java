package com.itheima.consultant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.itheima.consultant.pojo.TurbineMonitorData;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * 风机运行监测数据表 Mapper 接口
 * </p>
 *
 * @author YourName
 * @since 2026-03-13
 */
@Mapper
public interface TurbineMonitorDataMapper extends BaseMapper<TurbineMonitorData> {

    // 这里不需要写任何方法！
    // MyBatis Plus 已经自动提供了：
    // selectById, selectList, selectPage, insert, updateById, deleteById 等所有基础方法。

    // 如果未来有极其复杂的原生 SQL 需求（如多表关联统计），可以在这里定义方法，
    // 然后配合 @Select 注解或 XML 文件实现。但在当前 RAG+Agent 场景下，BaseMapper 足够用了。
}
