package com.xspaceagi.system.web.controller;

import com.xspaceagi.system.application.dto.UsageDto;
import com.xspaceagi.system.application.dto.UserMetricDto;
import com.xspaceagi.system.application.service.UserMetricApplicationService;
import com.xspaceagi.system.sdk.server.IUserDataPermissionRpcService;
import com.xspaceagi.system.sdk.service.dto.BizType;
import com.xspaceagi.system.sdk.service.dto.PeriodType;
import com.xspaceagi.system.sdk.service.dto.PeriodUtils;
import com.xspaceagi.system.sdk.service.dto.UserDataPermissionDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.PeriodTypeEnum;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Tag(name = "用户计量统计", description = "用户计量相关接口")
@RestController
@RequestMapping("/api/user/metric")
public class UserMetricController {

    @Resource
    private UserMetricApplicationService userMetricApplicationService;

    @Resource
    private IUserDataPermissionRpcService userDataPermissionRpcService;

    @Operation(summary = "查询用户各项已使用情况")
    @GetMapping("/usage")
    public ReqResult<UsageDto> usage() {
        UserDataPermissionDto userDataPermission = userDataPermissionRpcService.getUserDataPermission(RequestContext.get().getUserId());
        UsageDto usageDto = new UsageDto();
        List<UserMetricDto> userMetricDtos = userMetricApplicationService.queryByUserId(RequestContext.get().getUserId(), PeriodUtils.getCurrentPeriod(PeriodTypeEnum.DAY.getCode()));
        Map<String, BigInteger> bizMap = userMetricDtos.stream().collect(Collectors.toMap(userMetricDto -> userMetricDto.getBizType() + "-" + userMetricDto.getPeriodType(), val -> val.getValue().toBigInteger(), (v1, v2) -> v1));
        Object todayTokenUsage = bizMap.get(BizType.TOKEN_USAGE.name() + "-" + PeriodType.DAY);
        if (todayTokenUsage == null) {
            todayTokenUsage = "0";
        }
        UsageDto.Usage tokenUsage = UsageDto.Usage.builder().usage(todayTokenUsage.toString()).limit(userDataPermission.getTokenLimit().getLimitPerDay() == -1 ? "不限" : userDataPermission.getTokenLimit().getLimitPerDay().toString()).build();
        tokenUsage.setDescription(tokenUsage.getUsage() + "/" + tokenUsage.getLimit());
        usageDto.setTodayTokenUsage(tokenUsage);

        Object generalAgentChatUsage = bizMap.get(BizType.GENERAL_AGENT_CHAT.name() + "-" + PeriodType.DAY);
        if (generalAgentChatUsage == null) {
            generalAgentChatUsage = "0";
        }
        usageDto.setTodayAgentPromptUsage(UsageDto.Usage.builder().usage(generalAgentChatUsage.toString()).limit(userDataPermission.getAgentDailyPromptLimit() == -1 ? "不限" : userDataPermission.getAgentDailyPromptLimit().toString()).build());
        usageDto.getTodayAgentPromptUsage().setDescription(usageDto.getTodayAgentPromptUsage().getUsage() + "/" + usageDto.getTodayAgentPromptUsage().getLimit());

        Object appDevChatUsage = bizMap.get(BizType.APP_DEV_CHAT.name() + "-" + PeriodType.DAY);
        if (appDevChatUsage == null) {
            appDevChatUsage = 0;
        }
        usageDto.setTodayPageAppPromptUsage(UsageDto.Usage.builder().usage(appDevChatUsage.toString()).limit(userDataPermission.getPageDailyPromptLimit() == -1 ? "不限" : userDataPermission.getPageDailyPromptLimit().toString()).build());
        usageDto.getTodayPageAppPromptUsage().setDescription(usageDto.getTodayPageAppPromptUsage().getUsage() + "/" + usageDto.getTodayPageAppPromptUsage().getLimit());
        usageDto.setNewAgentUsage(UsageDto.Usage.builder().description(userDataPermission.getMaxAgentCount() == -1 ? "不限" : userDataPermission.getMaxAgentCount().toString()).build());
        usageDto.setNewTableUsage(UsageDto.Usage.builder().description(userDataPermission.getMaxDataTableCount() == -1 ? "不限" : userDataPermission.getMaxDataTableCount().toString()).build());
        usageDto.setNewTaskUsage(UsageDto.Usage.builder().description(userDataPermission.getMaxScheduledTaskCount() == -1 ? "不限" : userDataPermission.getMaxScheduledTaskCount().toString()).build());
        usageDto.setNewKnowledgeBaseUsage(UsageDto.Usage.builder().description(userDataPermission.getMaxKnowledgeCount() == -1 ? "不限" : userDataPermission.getMaxKnowledgeCount().toString()).build());
        usageDto.setKnowledgeBaseStorageUsage(UsageDto.Usage.builder().description(userDataPermission.getKnowledgeStorageLimitGb().compareTo(BigDecimal.valueOf(-1L)) == 0 ? "不限" : userDataPermission.getKnowledgeStorageLimitGb().toBigInteger() + "GB").build());
        usageDto.setNewWorkspaceUsage(UsageDto.Usage.builder().description(userDataPermission.getMaxSpaceCount() == -1 ? "不限" : userDataPermission.getMaxSpaceCount().toString()).build());
        usageDto.setSandboxMemoryLimit(UsageDto.Usage.builder().description(userDataPermission.getAgentComputerMemoryGb() == null ? "不限" : userDataPermission.getAgentComputerMemoryGb().toString()).build());
        usageDto.setNewPageAppUsage(UsageDto.Usage.builder().description(userDataPermission.getMaxPageAppCount() == -1 ? "不限" : userDataPermission.getMaxPageAppCount().toString()).build());

        return ReqResult.success(usageDto);
    }
}
