package com.xspaceagi.credit.app.service;

import com.xspaceagi.credit.sdk.dto.*;
import com.xspaceagi.credit.spec.enums.CreditTypeEnum;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface CreditService {

    /**
     * 添加用户积分
     */
    String addCredit(CreditAddRequest request);

    /**
     * 扣减用户积分
     */
    boolean deductCredit(CreditDeductRequest request);

    /**
     * 获取用户总积分
     */
    UserCreditSummary getUserCreditSummary(Long userId);

    /**
     * 分页查询用户积分汇总列表
     */
    UserCreditSummaryPageDTO queryUserCreditSummary(Long userId, Integer pageNum, Integer pageSize);

    /**
     * 获取用户积分批次列表
     */
    List<UserCreditBatchDTO> getUserCreditBatches(Long userId, CreditTypeEnum creditType, Boolean expired);

    /**
     * 获取用户积分流水明细（keyset分页，lastId为空查第一页）
     */
    List<CreditFlowDTO> getCreditFlows(Long userId, CreditTypeEnum creditType, Long lastId, Integer pageSize);

    /**
     * 获取用户指定类型的积分数量
     */
    BigDecimal getCreditsByType(Long userId, CreditTypeEnum creditType);

    /**
     * 检查用户积分是否足够
     */
    boolean checkBalance(Long userId, BigDecimal requiredAmount);

    /**
     * 获取用户积分统计信息
     */
    Map<String, Object> getCreditStatistics(Long userId);
}
