package com.xspaceagi.pay.application.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xspaceagi.pay.application.service.PayDeveloperAccountAdminApplicationService;
import com.xspaceagi.pay.application.service.support.PayDeveloperAccountAssembler;
import com.xspaceagi.pay.application.service.support.PayDeveloperAccountUserNameSupport;
import com.xspaceagi.pay.application.support.PayShanghaiDates;
import com.xspaceagi.pay.domain.model.PayDeveloperAccountModel;
import com.xspaceagi.pay.domain.model.PayDeveloperAccountPageSlice;
import com.xspaceagi.pay.domain.repository.PayDeveloperAccountRepository;
import com.xspaceagi.pay.spec.dto.PageResult;
import com.xspaceagi.pay.spec.dto.PayDeveloperAccountAdminPageRequest;
import com.xspaceagi.pay.spec.dto.PayDeveloperAccountAdminSaveRequest;
import com.xspaceagi.pay.spec.dto.PayDeveloperAccountAdminUserQueryRequest;
import com.xspaceagi.pay.spec.dto.PayDeveloperAccountByIdRequest;
import com.xspaceagi.pay.spec.dto.PayDeveloperAccountResponse;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.dto.UserQueryDto;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.PageQueryVo;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import jakarta.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

@Service
public class PayDeveloperAccountAdminApplicationServiceImpl implements PayDeveloperAccountAdminApplicationService {

    private static final long USER_NAME_SEARCH_PAGE_SIZE = 500L;

    @Resource
    private PayDeveloperAccountRepository payDeveloperAccountRepository;

    @Resource
    private UserApplicationService userApplicationService;

    @Override
    public PayDeveloperAccountResponse save(PayDeveloperAccountAdminSaveRequest request) {
        Assert.notNull(request.getUserId(), "userId must not be null");
        long tenantId = resolveContextTenantId();
        Map<Long, String> userNames = PayDeveloperAccountUserNameSupport.loadUserNameMap(userApplicationService, List.of(request.getUserId()));
        if (request.getId() != null) {
            PayDeveloperAccountModel existing =
                    payDeveloperAccountRepository
                            .findById(request.getId())
                            .orElseThrow(() -> BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.pay_developer_account_not_found));
            PayDeveloperAccountAssembler.applyAdminSave(request, existing);
            existing.setTenantId(tenantId);
            existing.setUserId(request.getUserId());
            return PayDeveloperAccountAssembler.toResponse(payDeveloperAccountRepository.save(existing), userNames);
        }
        return payDeveloperAccountRepository
                .findByTenantIdAndUserId(tenantId, request.getUserId())
                .map(
                        row -> {
                            PayDeveloperAccountAssembler.applyAdminSave(request, row);
                            row.setTenantId(tenantId);
                            row.setUserId(request.getUserId());
                            return PayDeveloperAccountAssembler.toResponse(payDeveloperAccountRepository.save(row), userNames);
                        })
                .orElseGet(
                        () -> {
                            PayDeveloperAccountModel row = PayDeveloperAccountModel.builder()
                                    .tenantId(tenantId)
                                    .userId(request.getUserId())
                                    .build();
                            PayDeveloperAccountAssembler.applyAdminSave(request, row);
                            return PayDeveloperAccountAssembler.toResponse(payDeveloperAccountRepository.save(row), userNames);
                        });
    }

    @Override
    public PayDeveloperAccountResponse getById(Long id) {
        Assert.notNull(id, "id must not be null");
        return payDeveloperAccountRepository
                .findById(id)
                .map(
                        m -> {
                            Map<Long, String> userNames =
                                    PayDeveloperAccountUserNameSupport.loadUserNameMap(
                                            userApplicationService, List.of(m.getUserId()));
                            return PayDeveloperAccountAssembler.toResponse(m, userNames);
                        })
                .orElseThrow(() -> BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.pay_developer_account_not_found));
    }

    @Override
    public PayDeveloperAccountResponse getByUser(PayDeveloperAccountAdminUserQueryRequest request) {
        Assert.notNull(request.getUserId(), "userId must not be null");
        long tenantId = resolveContextTenantId();
        return payDeveloperAccountRepository
                .findByTenantIdAndUserId(tenantId, request.getUserId())
                .map(
                        m -> {
                            Map<Long, String> userNames =
                                    PayDeveloperAccountUserNameSupport.loadUserNameMap(
                                            userApplicationService, List.of(m.getUserId()));
                            return PayDeveloperAccountAssembler.toResponse(m, userNames);
                        })
                .orElse(null);
    }

    @Override
    public void deleteById(PayDeveloperAccountByIdRequest request) {
        payDeveloperAccountRepository
                .findById(request.getId())
                .orElseThrow(() -> BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.pay_developer_account_not_found));
        payDeveloperAccountRepository.deleteById(request.getId());
    }

    @Override
    public PageResult<PayDeveloperAccountResponse> page(PayDeveloperAccountAdminPageRequest request) {
        long tenantId = resolveContextTenantId();
        int page = request.getPage() == null || request.getPage() < 1 ? 1 : request.getPage();
        int pageSize = request.getPageSize() == null || request.getPageSize() < 1 ? 20 : request.getPageSize();
        if (pageSize > 200) {
            pageSize = 200;
        }
        Long userIdEq = null;
        List<Long> userIdIn = null;
        if (StringUtils.hasText(request.getUserNameKeyword())) {
            List<Long> matchedUserIds = resolveUserIdsByUserNameKeyword(request.getUserNameKeyword().trim());
            if (matchedUserIds.isEmpty()) {
                return PageResult.<PayDeveloperAccountResponse>builder()
                        .records(List.of())
                        .total(0)
                        .page(page)
                        .pageSize(pageSize)
                        .build();
            }
            if (request.getUserId() != null) {
                if (!matchedUserIds.contains(request.getUserId())) {
                    return PageResult.<PayDeveloperAccountResponse>builder()
                            .records(List.of())
                            .total(0)
                            .page(page)
                            .pageSize(pageSize)
                            .build();
                }
                userIdEq = request.getUserId();
            } else {
                userIdIn = matchedUserIds;
            }
        } else if (request.getUserId() != null) {
            userIdEq = request.getUserId();
        }
        PayDeveloperAccountPageSlice slice =
                payDeveloperAccountRepository.pageByTenantAndFilters(
                        tenantId,
                        userIdEq,
                        userIdIn,
                        PayShanghaiDates.fromWallShanghai(request.getCreatedStart()),
                        PayShanghaiDates.fromWallShanghai(request.getCreatedEnd()),
                        page,
                        pageSize);
        List<Long> userIds =
                slice.records().stream()
                        .map(PayDeveloperAccountModel::getUserId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();
        Map<Long, String> userNames = PayDeveloperAccountUserNameSupport.loadUserNameMap(userApplicationService, userIds);
        List<PayDeveloperAccountResponse> records =
                slice.records().stream()
                        .map(m -> PayDeveloperAccountAssembler.toResponse(m, userNames))
                        .toList();
        return PageResult.<PayDeveloperAccountResponse>builder()
                .records(records)
                .total(slice.total())
                .page(page)
                .pageSize(pageSize)
                .build();
    }

    /** 调用系统 UserApplicationService.listQuery，检索规则与前台用户搜索一致（含邮箱/手机/昵称模糊等）。 */
    private List<Long> resolveUserIdsByUserNameKeyword(String keyword) {
        PageQueryVo<UserQueryDto> pageQueryVo = new PageQueryVo<>();
        pageQueryVo.setPageNo(1L);
        pageQueryVo.setPageSize(USER_NAME_SEARCH_PAGE_SIZE);
        UserQueryDto userQueryDto = new UserQueryDto();
        userQueryDto.setUserName(keyword);
        pageQueryVo.setQueryFilter(userQueryDto);
        IPage<UserDto> userPage = userApplicationService.listQuery(pageQueryVo);
        return userPage.getRecords().stream()
                .map(UserDto::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private static long resolveContextTenantId() {
        Long tenantId = RequestContext.get() != null ? RequestContext.get().getTenantId() : null;
        if (tenantId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemGetTenantFailed);
        }
        return tenantId;
    }
}
