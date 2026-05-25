package com.xspaceagi.bill.infra.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xspaceagi.bill.infra.dao.entity.BillWithdrawApplication;
import com.xspaceagi.bill.sdk.dto.WithdrawQueryRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface BillWithdrawApplicationMapper extends BaseMapper<BillWithdrawApplication> {

    List<BillWithdrawApplication> selectListWithFilters(@Param("query") WithdrawQueryRequest query,
                                                           @Param("offset") int offset,
                                                           @Param("limit") int limit);

    Long countWithFilters(@Param("query") WithdrawQueryRequest query);
}
