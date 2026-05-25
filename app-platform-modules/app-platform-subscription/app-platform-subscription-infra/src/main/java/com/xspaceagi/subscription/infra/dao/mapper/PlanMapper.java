package com.xspaceagi.subscription.infra.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xspaceagi.subscription.infra.dao.entity.Plan;
import com.xspaceagi.subscription.sdk.dto.PlanQueryRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PlanMapper extends BaseMapper<Plan> {

    List<Plan> selectListWithFilters(@Param("query") PlanQueryRequest query);
}
