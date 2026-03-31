package com.xspaceagi.system.web.controller;

import com.xspaceagi.system.application.dto.UpdateApiKeyDto;
import com.xspaceagi.system.application.service.UserApiKeyApplicationService;
import com.xspaceagi.system.infra.dao.entity.OpenApiDefinition;
import com.xspaceagi.system.sdk.service.dto.UserAccessKeyDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.web.dto.ApiInvokeStatDto;
import com.xspaceagi.system.web.dto.ApiKeyCreateDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Tag(name = "用户APIKEY管理", description = "用户APIKEY管理相关接口")
@RestController
@RequestMapping("/api/user/api-key")
public class UserApiKeyController {

    @Resource
    private UserApiKeyApplicationService userApiKeyApplicationService;

    @Operation(summary = "新增创建APIKEY")
    @PostMapping("/create")
    public ReqResult<UserAccessKeyDto> create(@RequestBody ApiKeyCreateDto apiKeyCreateDto) {
        UserAccessKeyDto userApiKey = userApiKeyApplicationService.createUserApiKey(RequestContext.get().getUserId(), apiKeyCreateDto.getName(), apiKeyCreateDto.getExpire());
        return ReqResult.success(userApiKey);
    }

    @Operation(summary = "更新APIKEY")
    @PostMapping("/update")
    public ReqResult<Void> update(@RequestBody UpdateApiKeyDto updateApiKeyDto) {
        updateApiKeyDto.setUserId(RequestContext.get().getUserId());
        userApiKeyApplicationService.updateUserApiKey(updateApiKeyDto);
        return ReqResult.success();
    }

    @Operation(summary = "删除APIKEY")
    @PostMapping("/delete/{apiKey}")
    public ReqResult<Void> delete(@PathVariable String apiKey) {
        userApiKeyApplicationService.deleteUserApiKey(RequestContext.get().getUserId(), apiKey);
        return ReqResult.success();
    }

    @Operation(summary = "查询用户APIKEY列表")
    @GetMapping("/list")
    public ReqResult<List<UserAccessKeyDto>> list() {
        List<UserAccessKeyDto> userApiKeys = userApiKeyApplicationService.getUserApiKeys(RequestContext.get().getUserId());
        return ReqResult.success(userApiKeys);
    }

    @Operation(summary = "获取用户有权限的API列表")
    @GetMapping("/open-api-definitions")
    public ReqResult<List<OpenApiDefinition>> openApiDefinitions() {
        List<OpenApiDefinition> openApiDefinitions = userApiKeyApplicationService.queryOpenApiDefinitions(RequestContext.get().getUserId());
        return ReqResult.success(openApiDefinitions);
    }

    @Operation(summary = "APIKEY对应的接口调用统计")
    @GetMapping("/stats")
    public ReqResult<List<ApiInvokeStatDto>> stats() {
        List<OpenApiDefinition> openApiDefinitions = userApiKeyApplicationService.queryOpenApiDefinitions(RequestContext.get().getUserId());
        List<ApiInvokeStatDto> apiInvokeStats = new ArrayList<>();
        openApiDefinitions.forEach(openApiDefinition -> {
            if (CollectionUtils.isNotEmpty(openApiDefinition.getApiList())) {
                openApiDefinition.getApiList().forEach(apiDefinition -> {
                    ApiInvokeStatDto apiInvokeStat = new ApiInvokeStatDto();
                    apiInvokeStat.setName(apiDefinition.getName());
                    apiInvokeStat.setPath(apiDefinition.getPath());
                    apiInvokeStat.setKey(apiDefinition.getKey());
                    apiInvokeStat.setTotal(ApiInvokeStatDto.InvokeCount.builder().failCount(0L).successCount(100L).totalCount(20L).build());
                    apiInvokeStat.setToday(ApiInvokeStatDto.InvokeCount.builder().failCount(0L).successCount(100L).totalCount(20L).build());
                    apiInvokeStat.setYesterday(ApiInvokeStatDto.InvokeCount.builder().failCount(0L).successCount(100L).totalCount(20L).build());
                    apiInvokeStat.setWeek(ApiInvokeStatDto.InvokeCount.builder().failCount(0L).successCount(100L).totalCount(20L).build());
                    apiInvokeStat.setMonth(ApiInvokeStatDto.InvokeCount.builder().failCount(0L).successCount(100L).totalCount(20L).build());
                    apiInvokeStats.add(apiInvokeStat);
                });
            }
        });
        return ReqResult.success(apiInvokeStats);
    }
}
