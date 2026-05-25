package com.xspaceagi.bill.app.service.impl;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.bill.app.service.BillWithdrawAppService;
import com.xspaceagi.bill.infra.dao.entity.BillDailyRevenue;
import com.xspaceagi.bill.infra.dao.entity.BillWithdrawApplication;
import com.xspaceagi.bill.infra.dao.entity.BillWithdrawConfig;
import com.xspaceagi.bill.infra.dao.entity.BillWithdrawRevenueRef;
import com.xspaceagi.bill.infra.dao.mapper.BillWithdrawApplicationMapper;
import com.xspaceagi.bill.infra.dao.mapper.BillWithdrawRevenueRefMapper;
import com.xspaceagi.bill.infra.dao.service.IBillDailyRevenueService;
import com.xspaceagi.bill.infra.dao.service.IBillWithdrawApplicationService;
import com.xspaceagi.bill.infra.dao.service.IBillWithdrawConfigService;
import com.xspaceagi.bill.infra.dao.service.IBillWithdrawRevenueRefService;
import com.xspaceagi.bill.sdk.dto.*;
import com.xspaceagi.bill.spec.enums.LimitModeEnum;
import com.xspaceagi.bill.spec.enums.RevenueStatusEnum;
import com.xspaceagi.bill.spec.enums.WithdrawStatusEnum;
import com.xspaceagi.pay.sdk.dto.DeveloperAccountInfoResponse;
import com.xspaceagi.pay.sdk.service.IDeveloperAccountInfoRpcService;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.sdk.server.IUserRpcService;
import com.xspaceagi.system.sdk.service.INotificationRpcService;
import com.xspaceagi.system.sdk.service.dto.UserDetailDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.utils.I18nUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class BillWithdrawAppServiceImpl implements BillWithdrawAppService {

    @Resource
    private IBillWithdrawConfigService billWithdrawConfigService;

    @Resource
    private IBillWithdrawApplicationService billWithdrawApplicationService;

    @Resource
    private IBillDailyRevenueService billDailyRevenueService;

    @Resource
    private IBillWithdrawRevenueRefService billWithdrawRevenueRefService;

    @Resource
    private BillWithdrawRevenueRefMapper billWithdrawRevenueRefMapper;

    @Resource
    private BillWithdrawApplicationMapper billWithdrawApplicationMapper;

    @Resource
    private IUserRpcService iUserRpcService;

    @Resource
    private INotificationRpcService iNotificationRpcService;

    @Resource
    private IDeveloperAccountInfoRpcService developerAccountInfoRpcService;

    @Override
    public WithdrawConfigDTO getWithdrawConfig(Long tenantId) {
        BillWithdrawConfig config = billWithdrawConfigService.lambdaQuery()
                .eq(BillWithdrawConfig::getTenantId, tenantId)
                .one();
        return config != null ? convertConfigToDTO(config) : null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean saveWithdrawConfig(SaveWithdrawConfigRequest request) {
        BillWithdrawConfig existing = billWithdrawConfigService.lambdaQuery()
                .eq(BillWithdrawConfig::getTenantId, request.getTenantId())
                .one();
        if (existing != null) {
            existing.setMinAmount(request.getMinAmount());
            existing.setMonthlyLimit(request.getMonthlyLimit());
            existing.setDailyLimit(request.getDailyLimit());
            existing.setLimitMode(request.getLimitMode() != null ? request.getLimitMode().getCode() : null);
            return billWithdrawConfigService.updateById(existing);
        } else {
            BillWithdrawConfig config = BillWithdrawConfig.builder()
                    .tenantId(request.getTenantId())
                    .minAmount(request.getMinAmount())
                    .monthlyLimit(request.getMonthlyLimit())
                    .dailyLimit(request.getDailyLimit())
                    .limitMode(request.getLimitMode() != null ? request.getLimitMode().getCode() : null)
                    .build();
            return billWithdrawConfigService.save(config);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WithdrawApplicationDTO createWithdrawApplication(Long tenantId, Long userId) {
        BillWithdrawConfig config = billWithdrawConfigService.lambdaQuery()
                .eq(BillWithdrawConfig::getTenantId, tenantId)
                .one();
        if (config == null) {
            throw new BizException("CONFIG_NOT_FOUND", I18nUtil.systemMessage("Backend.Bill.Withdraw.Error.ConfigNotFound"));
        }
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        List<BillDailyRevenue> pendingRevenues = billDailyRevenueService.lambdaQuery()
                .eq(BillDailyRevenue::getUserId, userId)
                .eq(BillDailyRevenue::getStatus, RevenueStatusEnum.PENDING.getCode())
                .lt(BillDailyRevenue::getDt, today)
                .list();
        if (pendingRevenues.isEmpty()) {
            throw new BizException("NO_PENDING_REVENUE", I18nUtil.systemMessage("Backend.Bill.Withdraw.Error.NoPendingRevenue"));
        }

        BigDecimal totalAmount = pendingRevenues.stream()
                .map(BillDailyRevenue::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (config.getMinAmount() != null && totalAmount.compareTo(config.getMinAmount()) < 0) {
            throw new BizException("BELOW_MIN_AMOUNT", I18nUtil.systemMessage("Backend.Bill.Withdraw.Error.BelowMinAmount"));
        }

        DeveloperAccountInfoResponse developerAccountInfo = developerAccountInfoRpcService.getDeveloperAccountInfo(tenantId, userId);
        if (developerAccountInfo == null || StringUtils.isBlank(developerAccountInfo.getBankCardNo()) || StringUtils.isBlank(developerAccountInfo.getBankName()) || StringUtils.isBlank(developerAccountInfo.getRealName())) {
            throw new BizException("DEVELOPER_ACCOUNT_NOT_FOUND", I18nUtil.systemMessage("Backend.Bill.Withdraw.Error.DeveloperAccountIncomplete"));
        }

        // Check daily limit
        if (config.getDailyLimit() != null) {
            long todayCount = billWithdrawApplicationService.lambdaQuery()
                    .eq(BillWithdrawApplication::getUserId, userId)
                    .ge(BillWithdrawApplication::getCreated, today)
                    .count();
            if (todayCount >= config.getDailyLimit()) {
                if (LimitModeEnum.ALL.equals(LimitModeEnum.fromCode(config.getLimitMode()))) {
                    throw new BizException("DAILY_LIMIT_EXCEEDED", I18nUtil.systemMessage("Backend.Bill.Withdraw.Error.DailyLimitExceeded"));
                }
            }
        }

        // Check monthly limit
        if (config.getMonthlyLimit() != null) {
            String monthStart = LocalDate.now().withDayOfMonth(1).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            long monthCount = billWithdrawApplicationService.lambdaQuery()
                    .eq(BillWithdrawApplication::getUserId, userId)
                    .ge(BillWithdrawApplication::getCreated, monthStart)
                    .count();
            if (monthCount >= config.getMonthlyLimit()) {
                LimitModeEnum limitMode = LimitModeEnum.fromCode(config.getLimitMode());
                if (limitMode == null || LimitModeEnum.ALL.equals(limitMode) || LimitModeEnum.ANY.equals(limitMode)) {
                    throw new BizException("MONTHLY_LIMIT_EXCEEDED", I18nUtil.systemMessage("Backend.Bill.Withdraw.Error.MonthlyLimitExceeded"));
                }
            }
        }
        TenantConfigDto tenantConfig = (TenantConfigDto) RequestContext.get().getTenantConfig();
        double revenueRatio = tenantConfig.getRevenueRatio() == null || tenantConfig.getRevenueRatio() <= 0 || tenantConfig.getRevenueRatio() > 1 ? 0 : tenantConfig.getRevenueRatio();
        BigDecimal fee = totalAmount.multiply(BigDecimal.valueOf(revenueRatio));
        BillWithdrawApplication application = BillWithdrawApplication.builder()
                .userId(userId)
                .amount(totalAmount)
                .fee(fee)
                .actualAmount(totalAmount.subtract(fee))
                .status(WithdrawStatusEnum.PENDING_REVIEW.getCode())
                .build();
        billWithdrawApplicationService.save(application);

        List<BillWithdrawRevenueRef> refs = new ArrayList<>();
        for (BillDailyRevenue revenue : pendingRevenues) {
            refs.add(BillWithdrawRevenueRef.builder()
                    .applicationId(application.getId())
                    .revenueId(revenue.getId())
                    .build());
        }
        billWithdrawRevenueRefService.saveBatch(refs);

        // Update revenue status to WITHDRAW_APPLYING
        for (BillDailyRevenue revenue : pendingRevenues) {
            revenue.setStatus(RevenueStatusEnum.WITHDRAW_APPLYING.getCode());
            billDailyRevenueService.updateById(revenue);
        }

        log.info("创建提现申请, applicationId={}, userId={}, amount={}", application.getId(), userId, totalAmount);
        return convertAppToDTO(application);
    }

    @Override
    public WithdrawApplicationPageDTO queryWithdrawApplications(WithdrawQueryRequest query) {
        int pageNum = query.getPageNum() != null ? query.getPageNum() : 1;
        int pageSize = query.getPageSize() != null ? query.getPageSize() : 20;
        int offset = (pageNum - 1) * pageSize;
        List<BillWithdrawApplication> list = billWithdrawApplicationMapper.selectListWithFilters(query, offset, pageSize);
        Long total = billWithdrawApplicationMapper.countWithFilters(query);

        WithdrawApplicationPageDTO page = new WithdrawApplicationPageDTO();
        page.setRecords(list.stream().map(this::convertAppToDTO).collect(Collectors.toList()));
        page.setTotal(total);
        page.setPageNum(pageNum);
        page.setPageSize(pageSize);
        return page;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean processWithdraw(WithdrawProcessRequest request) {
        BillWithdrawApplication application = billWithdrawApplicationService.getById(request.getApplicationId());
        if (application == null) {
            throw new BizException("APPLICATION_NOT_FOUND", I18nUtil.systemMessage("Backend.Bill.Withdraw.Error.ApplicationNotFound"));
        }

        List<BillWithdrawRevenueRef> refs = billWithdrawRevenueRefMapper
                .selectByApplicationId(request.getApplicationId());
        List<Long> revenueIds = refs.stream()
                .map(BillWithdrawRevenueRef::getRevenueId)
                .collect(Collectors.toList());

        switch (request.getAction()) {
            case "REJECT":
                if (request.getRejectReason() == null || request.getRejectReason().isBlank()) {
                    throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), I18nUtil.systemMessage("Backend.Bill.Withdraw.Validate.RejectReasonRequired"));
                }
                application.setStatus(WithdrawStatusEnum.REJECTED.getCode());
                application.setRejectReason(request.getRejectReason());
                billWithdrawApplicationService.updateById(application);

                billDailyRevenueService.lambdaUpdate()
                        .set(BillDailyRevenue::getStatus, RevenueStatusEnum.PENDING.getCode())
                        .in(BillDailyRevenue::getId, revenueIds)
                        .update();
                iNotificationRpcService.sendNotifyMessage(application.getUserId(), I18nUtil.systemMessage("Backend.Bill.Withdraw.Notify.Rejected", request.getRejectReason()));
                log.info("驳回提现申请, applicationId={}, reason={}", request.getApplicationId(), request.getRejectReason());
                break;

            case "APPROVE":
                application.setStatus(WithdrawStatusEnum.APPROVED.getCode());
                billWithdrawApplicationService.updateById(application);

                billDailyRevenueService.lambdaUpdate()
                        .set(BillDailyRevenue::getStatus, RevenueStatusEnum.PAYING.getCode())
                        .in(BillDailyRevenue::getId, revenueIds)
                        .update();
                log.info("通过提现申请, applicationId={}", request.getApplicationId());
                iNotificationRpcService.sendNotifyMessage(application.getUserId(), I18nUtil.systemMessage("Backend.Bill.Withdraw.Notify.Approved"));
                break;

            case "COMPLETE_PAYMENT":
                application.setStatus(WithdrawStatusEnum.PAID.getCode());
                if (request.getPaymentExtra() != null) {
                    application.setPaymentExtra(JSON.toJSONString(request.getPaymentExtra()));
                }
                billWithdrawApplicationService.updateById(application);

                billDailyRevenueService.lambdaUpdate()
                        .set(BillDailyRevenue::getStatus, RevenueStatusEnum.SETTLED.getCode())
                        .in(BillDailyRevenue::getId, revenueIds)
                        .update();
                log.info("打款完成, applicationId={}", request.getApplicationId());
                iNotificationRpcService.sendNotifyMessage(application.getUserId(), I18nUtil.systemMessage("Backend.Bill.Withdraw.Notify.Paid"));
                break;

            default:
                throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), I18nUtil.systemMessage("Backend.Bill.Withdraw.Validate.InvalidAction"));
        }
        return true;
    }

    private WithdrawApplicationDTO convertAppToDTO(BillWithdrawApplication entity) {
        WithdrawApplicationDTO dto = new WithdrawApplicationDTO();
        BeanUtils.copyProperties(entity, dto);
        dto.setStatus(WithdrawStatusEnum.fromCode(entity.getStatus()));
        if (entity.getPaymentExtra() != null) {
            try {
                dto.setPaymentExtra(JSON.parseObject(entity.getPaymentExtra()));
            } catch (Exception ignored) {
            }
        }

        UserDetailDto userDetailDto = iUserRpcService.queryUserDetailById(entity.getUserId());
        if (userDetailDto != null) {
            dto.setUserName(userDetailDto.getNickName() != null ? userDetailDto.getNickName() : userDetailDto.getUserName());
            dto.setPhone(userDetailDto.getPhone());
            dto.setEmail(userDetailDto.getEmail());
        }

        List<BillWithdrawRevenueRef> refs = billWithdrawRevenueRefMapper
                .selectByApplicationId(entity.getId());
        if (!refs.isEmpty()) {
            List<Long> revenueIds = refs.stream()
                    .map(BillWithdrawRevenueRef::getRevenueId)
                    .collect(Collectors.toList());
            List<BillDailyRevenue> revenues = billDailyRevenueService.listByIds(revenueIds);
            dto.setRevenues(revenues.stream().map(r -> {
                DailyRevenueDTO revDTO = new DailyRevenueDTO();
                BeanUtils.copyProperties(r, revDTO);
                revDTO.setStatus(RevenueStatusEnum.fromCode(r.getStatus()));
                revDTO.setUserName(dto.getUserName());
                revDTO.setPhone(dto.getPhone());
                revDTO.setEmail(dto.getEmail());
                return revDTO;
            }).collect(Collectors.toList()));
        }
        return dto;
    }

    private WithdrawConfigDTO convertConfigToDTO(BillWithdrawConfig entity) {
        WithdrawConfigDTO dto = new WithdrawConfigDTO();
        BeanUtils.copyProperties(entity, dto);
        dto.setLimitMode(LimitModeEnum.fromCode(entity.getLimitMode()));
        return dto;
    }
}
