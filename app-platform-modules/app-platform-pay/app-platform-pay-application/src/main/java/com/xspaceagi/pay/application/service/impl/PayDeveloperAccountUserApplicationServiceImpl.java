package com.xspaceagi.pay.application.service.impl;

import com.xspaceagi.pay.application.service.PayDeveloperAccountUserApplicationService;
import com.xspaceagi.pay.application.service.support.PayDeveloperAccountAssembler;
import com.xspaceagi.pay.application.service.support.PayDeveloperAccountUserNameSupport;
import com.xspaceagi.pay.domain.model.PayDeveloperAccountModel;
import com.xspaceagi.pay.domain.repository.PayDeveloperAccountRepository;
import com.xspaceagi.pay.sdk.dto.DeveloperAccountInfoResponse;
import com.xspaceagi.pay.sdk.service.IDeveloperAccountInfoRpcService;
import com.xspaceagi.pay.spec.dto.PayDeveloperAccountResponse;
import com.xspaceagi.pay.spec.dto.PayDeveloperAccountUserSaveRequest;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.HttpStatusEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class PayDeveloperAccountUserApplicationServiceImpl implements PayDeveloperAccountUserApplicationService, IDeveloperAccountInfoRpcService {

    @Resource
    private PayDeveloperAccountRepository payDeveloperAccountRepository;

    @Resource
    private UserApplicationService userApplicationService;

    @Override
    public PayDeveloperAccountResponse save(PayDeveloperAccountUserSaveRequest request) {
        long tenantId = resolveTenantId();
        long loginUserId = requireLoginUserId();
        Map<Long, String> userNames = PayDeveloperAccountUserNameSupport.loadUserNameMap(userApplicationService, List.of(loginUserId));
        if (request.getId() != null) {
            PayDeveloperAccountModel existing =
                    payDeveloperAccountRepository.findById(request.getId()).orElseThrow(() -> BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.pay_developer_account_not_found));
            if (!existing.getTenantId().equals(tenantId)) {
                throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
            }
            if (!existing.getUserId().equals(loginUserId)) {
                throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
            }
            PayDeveloperAccountAssembler.applyUserSave(request, existing);
            existing.setUserId(loginUserId);
            return PayDeveloperAccountAssembler.toResponse(payDeveloperAccountRepository.save(existing), userNames);
        }
        return payDeveloperAccountRepository
                .findByTenantIdAndUserId(tenantId, loginUserId)
                .map(
                        row -> {
                            PayDeveloperAccountAssembler.applyUserSave(request, row);
                            row.setUserId(loginUserId);
                            return PayDeveloperAccountAssembler.toResponse(payDeveloperAccountRepository.save(row), userNames);
                        })
                .orElseGet(
                        () -> {
                            PayDeveloperAccountModel row = PayDeveloperAccountModel.builder()
                                    .tenantId(tenantId)
                                    .userId(loginUserId)
                                    .build();
                            PayDeveloperAccountAssembler.applyUserSave(request, row);
                            row.setUserId(loginUserId);
                            return PayDeveloperAccountAssembler.toResponse(payDeveloperAccountRepository.save(row), userNames);
                        });
    }

    @Override
    public PayDeveloperAccountResponse getCurrent() {
        long tenantId = resolveTenantId();
        long loginUserId = requireLoginUserId();
        return payDeveloperAccountRepository
                .findByTenantIdAndUserId(tenantId, loginUserId)
                .map(
                        m ->
                                PayDeveloperAccountAssembler.toResponse(
                                        m,
                                        PayDeveloperAccountUserNameSupport.loadUserNameMap(
                                                userApplicationService, List.of(loginUserId))))
                .orElse(null);
    }

    @Override
    public DeveloperAccountInfoResponse getDeveloperAccountInfo(Long tenantId, Long userId) {
        return payDeveloperAccountRepository
                .findByTenantIdAndUserId(tenantId, userId)
                .map(
                        m ->
                                DeveloperAccountInfoResponse.builder()
                                        .id(m.getId())
                                        .tenantId(m.getTenantId())
                                        .userId(m.getUserId())
                                        .email(m.getEmail())
                                        .phone(m.getPhone())
                                        .idCardNo(m.getIdCardNo())
                                        .bankName(m.getBankName())
                                        .bankCardNo(m.getBankCardNo())
                                        .realName(m.getRealName())
                                        .build()
                ).orElse(null);
    }

    private static long resolveTenantId() {
        Long tenantId = RequestContext.get() != null ? RequestContext.get().getTenantId() : null;
        if (tenantId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemGetTenantFailed);
        }
        return tenantId;
    }

    private static long requireLoginUserId() {
        Long userId = RequestContext.get() != null ? RequestContext.get().getUserId() : null;
        if (userId == null) {
            throw BizException.of(HttpStatusEnum.UNAUTHORIZED, ErrorCodeEnum.UNAUTHORIZED, BizExceptionCodeEnum.systemUnauthorizedOrSessionExpired);
        }
        return userId;
    }
}
