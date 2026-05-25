package com.xspaceagi.bill.infra.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xspaceagi.bill.infra.dao.entity.BillResourceStat;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface BillResourceStatMapper extends BaseMapper<BillResourceStat> {

    List<Map<String, Object>> selectSummary(@Param("tenantId") Long tenantId,
                                             @Param("userId") Long userId,
                                             @Param("dtStart") String dtStart,
                                             @Param("dtEnd") String dtEnd);
}
