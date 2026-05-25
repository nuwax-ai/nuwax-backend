package com.xspaceagi.bill.infra.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xspaceagi.bill.infra.dao.entity.BillOrder;
import com.xspaceagi.bill.sdk.dto.OrderQueryRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface BillOrderMapper extends BaseMapper<BillOrder> {

    List<BillOrder> selectListWithFilters(@Param("query") OrderQueryRequest query,
                                           @Param("offset") int offset,
                                           @Param("limit") int limit);

    Long countWithFilters(@Param("query") OrderQueryRequest query);
}
