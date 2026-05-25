package com.xspaceagi.bill.infra.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xspaceagi.bill.infra.dao.entity.BillWithdrawRevenueRef;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface BillWithdrawRevenueRefMapper extends BaseMapper<BillWithdrawRevenueRef> {

    @Select("SELECT * FROM bill_withdraw_revenue_ref WHERE application_id = #{applicationId}")
    List<BillWithdrawRevenueRef> selectByApplicationId(@Param("applicationId") Long applicationId);
}
