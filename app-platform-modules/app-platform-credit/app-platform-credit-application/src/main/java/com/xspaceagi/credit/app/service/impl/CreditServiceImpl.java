package com.xspaceagi.credit.app.service.impl;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.credit.app.service.CreditService;
import com.xspaceagi.credit.infra.dao.entity.CreditFlow;
import com.xspaceagi.credit.infra.dao.entity.UserCredit;
import com.xspaceagi.credit.infra.dao.mapper.CreditFlowMapper;
import com.xspaceagi.credit.infra.dao.mapper.UserCreditMapper;
import com.xspaceagi.credit.sdk.dto.*;
import com.xspaceagi.credit.spec.enums.CreditOperationTypeEnum;
import com.xspaceagi.credit.spec.enums.CreditTypeEnum;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CreditServiceImpl implements CreditService {

    @Resource
    private UserCreditMapper userCreditMapper;

    @Resource
    private CreditFlowMapper creditFlowMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String addCredit(CreditAddRequest request) {
        // Idempotency check
        if (request.getBizNo() != null && !request.getBizNo().isEmpty()) {
            CreditFlow existing = creditFlowMapper.selectByBizNo(request.getBizNo(), CreditOperationTypeEnum.ADD.getCode());
            if (existing != null) {
                log.info("Credit grant idempotent return, bizNo={}, batchNo={}", request.getBizNo(), existing.getBatchNo());
                return existing.getBatchNo();
            }
        }

        String batchNo = generateBatchNo();
        List<CreditFlow> flowList = new ArrayList<>();
        BigDecimal beforeAdd = userCreditMapper.sumRemainAmountByUserId(request.getUserId());

        // 1. Create full credit batch
        UserCredit batch = createUserCredit(request, batchNo);
        userCreditMapper.insert(batch);

        // 2. Add credit flow
        CreditFlow addFlow = createCreditFlow(request, batchNo, beforeAdd);
        flowList.add(addFlow);

        // 3. Auto-repay loans: deduct from new batch
        if (request.getCreditType().getCode() <= CreditTypeEnum.MANUAL.getCode()) {
            repayLoansFromBatch(batch.getId());
        }

        // 4. Batch insert flows
        creditFlowMapper.batchInsert(flowList);
        return batchNo;
    }

    private static @NotNull CreditFlow createCreditFlow(CreditAddRequest request, String batchNo, BigDecimal beforeAdd) {
        CreditFlow addFlow = new CreditFlow();
        addFlow.setUserId(request.getUserId());
        addFlow.setBatchNo(batchNo);
        addFlow.setCreditType(request.getCreditType().getCode());
        addFlow.setOperationType(CreditOperationTypeEnum.ADD.getCode());
        addFlow.setAmount(request.getAmount());
        addFlow.setBeforeAmount(beforeAdd);
        addFlow.setAfterAmount(beforeAdd.add(request.getAmount()));
        addFlow.setBizNo(request.getBizNo());
        addFlow.setRemark(request.getRemark());
        return addFlow;
    }

    private static @NotNull UserCredit createUserCredit(CreditAddRequest request, String batchNo) {
        UserCredit batch = new UserCredit();
        batch.setUserId(request.getUserId());
        batch.setBatchNo(batchNo);
        batch.setCreditType(request.getCreditType().getCode());
        batch.setTotalAmount(request.getAmount());
        batch.setUsedAmount(BigDecimal.ZERO);
        batch.setRemainAmount(request.getAmount());
        batch.setExpireTime(request.getExpireTime());
        batch.setRepaidAmount(BigDecimal.ZERO);
        batch.setRepayStatus(0);
        batch.setRemark(request.getRemark());
        if (request.getExtra() != null) {
            batch.setExtra(JSON.toJSONString(request.getExtra()));
        }
        return batch;
    }

    /**
     * Deduct credits from specified batch for auto loan repayment
     */
    private void repayLoansFromBatch(Long batchId) {
        UserCredit batch = userCreditMapper.selectById(batchId);
        List<UserCredit> unpaidLoans = userCreditMapper.selectUnpaidLoans(batch.getUserId());
        BigDecimal available = batch.getRemainAmount();
        int batchVersion = batch.getVersion();
        for (UserCredit loan : unpaidLoans) {
            if (available.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }

            BigDecimal loanDebt = loan.getTotalAmount().subtract(
                    loan.getRepaidAmount() != null ? loan.getRepaidAmount() : BigDecimal.ZERO);
            BigDecimal repayAmount = loanDebt.min(available);

            // Update loan batch
            int newStatus = loanDebt.compareTo(repayAmount) <= 0 ? 1 : 0;
            int loanUpdated = userCreditMapper.updateRepayAmount(loan.getId(), repayAmount, newStatus, loan.getVersion());
            if (loanUpdated == 0) {
                log.warn("Loan repayment optimistic lock conflict, loanId={}", loan.getId());
                throw new BizException("OPTIMISTIC_LOCK_CONFLICT", "Loan repayment failed, please retry");
            }

            // Deduct from new batch
            int batchUpdated = userCreditMapper.updateRemainAmountWithVersion(batchId, repayAmount, batchVersion);
            if (batchUpdated == 0) {
                log.warn("Loan repayment deduction optimistic lock conflict, batchId={}", batchId);
                throw new BizException("OPTIMISTIC_LOCK_CONFLICT", "Loan repayment deduction failed, please retry");
            }
            batchVersion++;
            available = available.subtract(repayAmount);
            log.info("Loan repayment success, userId={}, loanBatchNo={}, repayAmount={}, remainDebt={}",
                    batch.getUserId(), loan.getBatchNo(), repayAmount, loanDebt.subtract(repayAmount));
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deductCredit(CreditDeductRequest request) {
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "Deduction amount must be greater than 0");
        }
        if (request.getCreditType() == null) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "Deduction type cannot be empty");
        }

        // Idempotency check
        if (request.getBizNo() != null && !request.getBizNo().isEmpty()) {
            CreditFlow existing = creditFlowMapper.selectByBizNo(request.getBizNo(), CreditOperationTypeEnum.DEDUCT.getCode());
            if (existing != null) {
                log.info("Credit deduction idempotent return, bizNo={}", request.getBizNo());
                return true;
            }
        }

        boolean allowNegative = Boolean.TRUE.equals(request.getAllowNegative());

        BigDecimal totalAmount = userCreditMapper.sumRemainAmountByUserId(request.getUserId());
        if (!allowNegative && (totalAmount == null || totalAmount.compareTo(request.getAmount()) < 0)) {
            throw new BizException("INSUFFICIENT_CREDIT", "Insufficient credit balance, current balance: " + totalAmount);
        }

        // 3. Get credit batches by priority: subscription > purchase > activity
        List<UserCredit> allValidCredits = userCreditMapper.selectValidCreditsOrderByExpireTime(request.getUserId());

        // 4. Group and sort by type
        List<UserCredit> activityCredits = filterCreditsByType(allValidCredits, CreditTypeEnum.ACTIVITY.getCode());
        List<UserCredit> subscriptionCredits = filterCreditsByType(allValidCredits, CreditTypeEnum.SUBSCRIPTION.getCode());
        List<UserCredit> purchaseCredits = filterCreditsByType(allValidCredits, CreditTypeEnum.PURCHASE.getCode());
        List<UserCredit> manualCredits = filterCreditsByType(allValidCredits, CreditTypeEnum.MANUAL.getCode());

        // 5. Deduct credits
        List<CreditFlow> flowList = new ArrayList<>();
        BigDecimal remainAmount = request.getAmount();

        remainAmount = deductCreditsByType(activityCredits, remainAmount, request, flowList);
        remainAmount = deductCreditsByType(manualCredits, remainAmount, request, flowList);
        remainAmount = deductCreditsByType(subscriptionCredits, remainAmount, request, flowList);
        remainAmount = deductCreditsByType(purchaseCredits, remainAmount, request, flowList);

        if (!allowNegative && remainAmount.compareTo(BigDecimal.ZERO) > 0) {
            throw new BizException("INSUFFICIENT_CREDIT", "Insufficient credit balance, remaining undeducted: " + remainAmount);
        }

        if (allowNegative && remainAmount.compareTo(BigDecimal.ZERO) > 0) {
            log.info("Create loan batch, userId={}, amount={}, loan={}", request.getUserId(), request.getAmount(), remainAmount);
            String loanBatchNo = generateBatchNo();
            UserCredit loanBatch = createLoanBatch(request, loanBatchNo, remainAmount);
            userCreditMapper.insert(loanBatch);
            deductCreditsByType(List.of(loanBatch), remainAmount, request, flowList);
        }

        // 6. Batch insert flows
        if (!flowList.isEmpty()) {
            creditFlowMapper.batchInsert(flowList);
        }

        return true;
    }

    private static @NotNull UserCredit createLoanBatch(CreditDeductRequest request, String loanBatchNo, BigDecimal remainAmount) {
        UserCredit loanBatch = new UserCredit();
        loanBatch.setUserId(request.getUserId());
        loanBatch.setBatchNo(loanBatchNo);
        loanBatch.setCreditType(CreditTypeEnum.LOAN.getCode());
        loanBatch.setTotalAmount(remainAmount);
        loanBatch.setUsedAmount(BigDecimal.ZERO);
        loanBatch.setRemainAmount(remainAmount);
        loanBatch.setRepaidAmount(BigDecimal.ZERO);
        loanBatch.setRepayStatus(0);
        loanBatch.setVersion(1);
        return loanBatch;
    }

    /**
     * Filter by credit type
     */
    private List<UserCredit> filterCreditsByType(List<UserCredit> credits, Integer creditType) {
        return credits.stream()
                .filter(credit -> credit.getCreditType().equals(creditType))
                .sorted(Comparator.comparing(UserCredit::getExpireTime, Comparator.nullsFirst(Comparator.naturalOrder())))
                .collect(Collectors.toList());
    }

    /**
     * Deduct from specified credit type batches
     */
    private BigDecimal deductCreditsByType(List<UserCredit> credits, BigDecimal needAmount,
                                           CreditDeductRequest request, List<CreditFlow> flowList) {
        BigDecimal remainNeed = needAmount;
        Long userId = request.getUserId();

        // Sort by expiry time, deduct soon-to-expire first
        for (UserCredit credit : credits) {
            if (remainNeed.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }

            BigDecimal availableAmount = credit.getRemainAmount();
            if (availableAmount.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            BigDecimal deductAmount = availableAmount.min(remainNeed);

            BigDecimal beforeAmount = userCreditMapper.sumRemainAmountByUserId(userId);

            // Use optimistic lock to update balance
            int updated = userCreditMapper.updateRemainAmountWithVersion(
                    credit.getId(),
                    deductAmount,
                    credit.getVersion()
            );

            if (updated > 0) {
                BigDecimal afterAmount = beforeAmount.subtract(deductAmount);

                // Create flow record, creditType uses deduction type (e.g. model call/agent call/tool call)
                CreditFlow flow = new CreditFlow();
                flow.setUserId(userId);
                flow.setBatchNo(credit.getBatchNo());
                flow.setCreditType(request.getCreditType().getCode());
                flow.setOperationType(CreditOperationTypeEnum.DEDUCT.getCode());
                flow.setAmount(deductAmount);
                flow.setBeforeAmount(beforeAmount);
                flow.setAfterAmount(afterAmount);
                flow.setBizNo(request.getBizNo());
                flow.setRemark(request.getRemark());

                flowList.add(flow);
                remainNeed = remainNeed.subtract(deductAmount);
            } else {
                // Optimistic lock conflict, retry or throw
                log.warn("Optimistic lock conflict, userId={} batchNo={} deduction failed", userId, credit.getBatchNo());
                throw new BizException("OPTIMISTIC_LOCK_CONFLICT", "Credit deduction failed, please retry");
            }
        }

        return remainNeed;
    }

    @Override
    public UserCreditSummary getUserCreditSummary(Long userId) {
        UserCreditSummary summary = new UserCreditSummary();
        summary.setUserId(userId);

        // Use cache to optimize query
        BigDecimal totalCredit = userCreditMapper.sumRemainAmountByUserId(userId);
        summary.setTotalCredit(totalCredit != null ? totalCredit : BigDecimal.ZERO);

        BigDecimal subscriptionCredit = userCreditMapper.sumRemainAmountByUserIdAndType(userId, CreditTypeEnum.SUBSCRIPTION.getCode());
        summary.setSubscriptionCredit(subscriptionCredit != null ? subscriptionCredit : BigDecimal.ZERO);

        BigDecimal purchaseCredit = userCreditMapper.sumRemainAmountByUserIdAndType(userId, CreditTypeEnum.PURCHASE.getCode());
        summary.setPurchaseCredit(purchaseCredit != null ? purchaseCredit : BigDecimal.ZERO);

        BigDecimal activityCredit = userCreditMapper.sumRemainAmountByUserIdAndType(userId, CreditTypeEnum.ACTIVITY.getCode());
        summary.setActivityCredit(activityCredit != null ? activityCredit : BigDecimal.ZERO);

        BigDecimal manualCredit = userCreditMapper.sumRemainAmountByUserIdAndType(userId, CreditTypeEnum.MANUAL.getCode());
        summary.setManualCredit(manualCredit != null ? manualCredit : BigDecimal.ZERO);

        return summary;
    }

    @Override
    public UserCreditSummaryPageDTO queryUserCreditSummary(Long userId, Integer pageNum, Integer pageSize) {
        int page = pageNum != null && pageNum > 0 ? pageNum : 1;
        int size = pageSize != null && pageSize > 0 ? pageSize : 20;
        int offset = (page - 1) * size;

        List<Map<String, Object>> rows = userCreditMapper.selectSummaryList(userId, offset, size);
        Long total = userCreditMapper.countSummaryList(userId);

        List<UserCreditSummary> records = rows.stream().map(row -> {
            UserCreditSummary summary = new UserCreditSummary();
            summary.setUserId(toLong(row.get("user_id")));
            summary.setTotalCredit(toBigDecimal(row.get("totalCredit")));
            summary.setSubscriptionCredit(toBigDecimal(row.get("subscriptionCredit")));
            summary.setPurchaseCredit(toBigDecimal(row.get("purchaseCredit")));
            summary.setActivityCredit(toBigDecimal(row.get("activityCredit")));
            summary.setManualCredit(toBigDecimal(row.get("manualCredit")));
            return summary;
        }).collect(Collectors.toList());

        UserCreditSummaryPageDTO pageDTO = new UserCreditSummaryPageDTO();
        pageDTO.setRecords(records);
        pageDTO.setTotal(total);
        pageDTO.setPageNum(page);
        pageDTO.setPageSize(size);
        return pageDTO;
    }

    @Override
    public List<UserCreditBatchDTO> getUserCreditBatches(Long userId, CreditTypeEnum creditType, Boolean expired) {
        if (userId == null) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "User ID cannot be empty");
        }

        List<UserCredit> credits;

        Integer typeCode = creditType != null ? creditType.getCode() : null;

        if (expired != null && expired) {
            credits = userCreditMapper.selectExpiredCredits(userId, typeCode);
        } else if (expired != null) {
            credits = userCreditMapper.selectValidCredits(userId, typeCode);
        } else {
            if (typeCode != null) {
                credits = userCreditMapper.selectByUserIdAndType(userId, typeCode);
            } else {
                credits = userCreditMapper.selectByUserId(userId);
            }
        }

        // Sort: created time descending
        credits.sort(Comparator.comparing(UserCredit::getCreated, Comparator.nullsLast(Comparator.reverseOrder())));

        return credits.stream().map(this::convertToBatchDTO).collect(Collectors.toList());
    }

    @Override
    public List<CreditFlowDTO> getCreditFlows(Long userId, CreditTypeEnum creditType, Long lastId, Integer pageSize) {
        if (userId == null) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "User ID cannot be empty");
        }
        if (pageSize == null || pageSize < 1 || pageSize > 100) {
            pageSize = 20;
        }

        Integer typeCode = creditType != null ? creditType.getCode() : null;

        List<CreditFlow> flows;
        if (lastId != null) {
            flows = creditFlowMapper.selectByUserIdAndTypeWithOffset(userId, typeCode, lastId, pageSize);
        } else {
            flows = creditFlowMapper.selectByUserIdAndType(userId, typeCode, pageSize);
        }

        List<CreditFlowDTO> creditFlowDTOS = flows.stream().map(this::convertToFlowDTO).collect(Collectors.toList());

        Map<String, List<CreditFlowDTO>> grouped = creditFlowDTOS.stream()
                .filter(dto -> dto.getBizNo() != null)
                .collect(Collectors.groupingBy(CreditFlowDTO::getBizNo));
        Set<Long> toRemove = new HashSet<>();
        for (List<CreditFlowDTO> group : grouped.values()) {
            if (group.size() > 1) {
                group.sort(Comparator.comparing(CreditFlowDTO::getId));
                CreditFlowDTO keeper = group.get(0);
                BigDecimal totalAmount = group.stream().map(CreditFlowDTO::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
                keeper.setAmount(totalAmount);
                group.subList(1, group.size()).forEach(dto -> toRemove.add(dto.getId()));
            }
        }
        creditFlowDTOS.removeIf(dto -> toRemove.contains(dto.getId()));
        return creditFlowDTOS;
    }

    @Override
    public BigDecimal getCreditsByType(Long userId, CreditTypeEnum creditType) {
        if (userId == null) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "User ID cannot be empty");
        }
        if (creditType == null) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "Credit type cannot be empty");
        }

        return userCreditMapper.sumRemainAmountByUserIdAndType(userId, creditType.getCode());
    }

    @Override
    public boolean checkBalance(Long userId, BigDecimal requiredAmount) {
        if (userId == null) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "User ID cannot be empty");
        }
        if (requiredAmount == null || requiredAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "Credit amount must be greater than 0");
        }

        BigDecimal totalCredit = userCreditMapper.sumRemainAmountByUserId(userId);
        return totalCredit != null && totalCredit.compareTo(requiredAmount) >= 0;
    }

    @Override
    public Map<String, Object> getCreditStatistics(Long userId) {
        if (userId == null) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "User ID cannot be empty");
        }

        Map<String, Object> statistics = new HashMap<>();

        // Get total credits
        BigDecimal totalCredit = userCreditMapper.sumRemainAmountByUserId(userId);
        statistics.put("totalCredit", totalCredit != null ? totalCredit : BigDecimal.ZERO);

        // Get credits by type
        Map<String, BigDecimal> typeCredits = new HashMap<>();
        typeCredits.put("SUBSCRIPTION", getCreditsByType(userId, CreditTypeEnum.SUBSCRIPTION));
        typeCredits.put("PURCHASE", getCreditsByType(userId, CreditTypeEnum.PURCHASE));
        typeCredits.put("ACTIVITY", getCreditsByType(userId, CreditTypeEnum.ACTIVITY));
        typeCredits.put("MANUAL", getCreditsByType(userId, CreditTypeEnum.MANUAL));

        statistics.put("typeCredits", typeCredits);

        // Get credits expiring soon (within 30 days)
        Date thirtyDaysLater = new Date(System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000);
        BigDecimal expiringSoon = userCreditMapper.sumExpiringCredits(userId, thirtyDaysLater);
        statistics.put("expiringSoon", expiringSoon != null ? expiringSoon : BigDecimal.ZERO);

        // Get total flow count
        int flowCount = creditFlowMapper.countByUserId(userId);
        statistics.put("totalFlows", flowCount);

        return statistics;
    }

    private UserCreditBatchDTO convertToBatchDTO(UserCredit credit) {
        UserCreditBatchDTO dto = new UserCreditBatchDTO();
        dto.setId(credit.getId());
        dto.setUserId(credit.getUserId());
        dto.setBatchNo(credit.getBatchNo());

        CreditTypeEnum typeEnum = CreditTypeEnum.getByCode(credit.getCreditType());
        dto.setCreditType(typeEnum);
        dto.setCreditTypeName(typeEnum != null ? typeEnum.getDesc() : "");

        dto.setTotalAmount(credit.getTotalAmount());
        dto.setUsedAmount(credit.getUsedAmount());
        dto.setRemainAmount(credit.getRemainAmount());
        dto.setExpireTime(credit.getExpireTime());

        boolean isExpired = credit.getExpireTime() != null && credit.getExpireTime().before(new Date());
        dto.setExpired(isExpired);

        dto.setCreated(credit.getCreated());
        dto.setRemark(credit.getRemark());
        if (JSON.isValidObject(credit.getExtra())){
            dto.setExtra(JSON.parseObject(credit.getExtra()));
        }
        return dto;
    }

    private CreditFlowDTO convertToFlowDTO(CreditFlow flow) {
        CreditFlowDTO dto = new CreditFlowDTO();
        dto.setId(flow.getId());
        dto.setUserId(flow.getUserId());
        dto.setBatchNo(flow.getBatchNo());

        CreditTypeEnum typeEnum = CreditTypeEnum.getByCode(flow.getCreditType());
        dto.setCreditType(typeEnum);
        dto.setCreditTypeName(typeEnum != null ? typeEnum.getDesc() : "");

        dto.setOperationType(flow.getOperationType());
        dto.setOperationTypeName(flow.getOperationType().equals(CreditOperationTypeEnum.ADD.getCode()) ?
                CreditOperationTypeEnum.ADD.getDesc() : CreditOperationTypeEnum.DEDUCT.getDesc());

        dto.setAmount(flow.getAmount());
        dto.setBeforeAmount(flow.getBeforeAmount());
        dto.setAfterAmount(flow.getAfterAmount());
        dto.setBizNo(flow.getBizNo());
        dto.setCreated(flow.getCreated());
        dto.setRemark(flow.getRemark());

        return dto;
    }

    private String generateBatchNo() {
        return "CREDIT_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private Long toLong(Object val) {
        if (val == null) return 0L;
        if (val instanceof Long) return (Long) val;
        if (val instanceof Number) return ((Number) val).longValue();
        return Long.parseLong(val.toString());
    }

    private BigDecimal toBigDecimal(Object val) {
        if (val == null) return BigDecimal.ZERO;
        BigDecimal result;
        if (val instanceof BigDecimal) result = (BigDecimal) val;
        else if (val instanceof Number) result = BigDecimal.valueOf(((Number) val).doubleValue());
        else result = new BigDecimal(val.toString());
        return result.setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
