package com.xspaceagi.credit.web.controller;

import com.xspaceagi.credit.app.service.CreditService;
import com.xspaceagi.credit.sdk.dto.*;
import com.xspaceagi.credit.spec.enums.CreditTypeEnum;
import com.xspaceagi.system.sdk.server.IUserRpcService;
import com.xspaceagi.system.sdk.service.dto.UserDetailDto;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.xspaceagi.system.spec.enums.ResourceEnum.SUBSCRIPTION_POINTS_MODIFY;
import static com.xspaceagi.system.spec.enums.ResourceEnum.SUBSCRIPTION_POINTS_QUERY;

@Slf4j
@RestController
@RequestMapping("/api/system/credit")
@Tag(name = "用户积分管理（管理端）")
public class CreditManController {

    @Resource
    private CreditService creditService;

    @Resource
    private IUserRpcService userRpcService;

    @RequireResource(SUBSCRIPTION_POINTS_QUERY)
    @GetMapping("/summary/list")
    @Operation(summary = "用户积分查询")
    public ReqResult<UserCreditSummaryPageDTO> getUserCreditSummary(@RequestParam(required = false) String usernamePhoneOrEmail,
                                                                    @RequestParam(required = false) Long userId,
                                                                    @RequestParam(defaultValue = "1") Integer pageNum,
                                                                    @RequestParam(defaultValue = "20") Integer pageSize) {
        Long queryUserId = userId;
        if (StringUtils.isNotBlank(usernamePhoneOrEmail)) {
            UserDetailDto userDetailDto = userRpcService.queryUserDetailByName(usernamePhoneOrEmail);
            if (userDetailDto != null) {
                queryUserId = userDetailDto.getId();
            } else {
                UserCreditSummaryPageDTO emptyPage = new UserCreditSummaryPageDTO();
                emptyPage.setRecords(List.of());
                emptyPage.setTotal(0L);
                emptyPage.setPageNum(pageNum);
                emptyPage.setPageSize(pageSize);
                return ReqResult.success(emptyPage);
            }
        }
        UserCreditSummaryPageDTO page = creditService.queryUserCreditSummary(queryUserId, pageNum, pageSize);
        if (!page.getRecords().isEmpty()) {
            List<Long> userIds = page.getRecords().stream().map(UserCreditSummary::getUserId).distinct().collect(Collectors.toList());
            List<UserContext> userContexts = userRpcService.queryUserListByIds(userIds);
            Map<Long, UserContext> userContextMap = userContexts.stream().collect(Collectors.toMap(UserContext::getUserId, u -> u, (a, b) -> a));
            page.getRecords().forEach(summary -> {
                UserContext userContext = userContextMap.get(summary.getUserId());
                if (userContext != null) {
                    summary.setUser(new CreditUser(userContext.getUserName(), userContext.getPhone(), userContext.getEmail()));
                }
            });
        }
        return ReqResult.success(page);
    }

    @RequireResource(SUBSCRIPTION_POINTS_QUERY)
    @GetMapping("/flow/list")
    @Operation(summary = "查询用户积分流水明细，传入lastId翻页")
    public ReqResult<List<CreditFlowDTO>> getCreditFlows(
            @RequestParam(required = false) String usernamePhoneOrEmail,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) CreditTypeEnum creditType,
            @RequestParam(required = false) Long lastId,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        UserDetailDto userDetailDto = null;
        if (StringUtils.isNotBlank(usernamePhoneOrEmail)) {
            userDetailDto = userRpcService.queryUserDetailByName(usernamePhoneOrEmail);
        }
        if (userId != null && userDetailDto == null) {
            userDetailDto = userRpcService.queryUserDetailById(userId);
        }
        if (userDetailDto == null) {
            return ReqResult.success(List.of());
        }
        List<CreditFlowDTO> creditFlows = creditService.getCreditFlows(userDetailDto.getId(), creditType, lastId == null ? Long.MAX_VALUE : lastId, pageSize);
        for (CreditFlowDTO creditFlowDTO : creditFlows) {
            creditFlowDTO.setUser(new CreditUser(userDetailDto.getUserName(), userDetailDto.getPhone(), userDetailDto.getEmail()));
        }
        return ReqResult.success(creditFlows);
    }

    @RequireResource(SUBSCRIPTION_POINTS_MODIFY)
    @PostMapping("/add")
    @Operation(summary = "积分发放")
    public ReqResult<String> addCredit(@RequestBody CreditAddRequest request) {
        request.setCreditType(request.getCreditType() == null || (request.getCreditType() != CreditTypeEnum.MANUAL && request.getCreditType() != CreditTypeEnum.ACTIVITY)
                ? CreditTypeEnum.MANUAL : request.getCreditType());//手动积分发放
        return ReqResult.success(creditService.addCredit(request));
    }

    @RequireResource(SUBSCRIPTION_POINTS_MODIFY)
    @PostMapping("/deduct")
    @Operation(summary = "积分扣减")
    public ReqResult<Boolean> deductCredit(@RequestBody CreditDeductRequest request) {
        request.setCreditType(CreditTypeEnum.MANUAL_DEDUCT);
        return ReqResult.success(creditService.deductCredit(request));
    }
}
