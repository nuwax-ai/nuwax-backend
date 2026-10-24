package com.xspaceagi.system.web.controller;

import com.xspaceagi.system.application.dto.UserConfigItemDto;
import com.xspaceagi.system.application.service.UserConfigApplicationService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.web.dto.UserConfigSetDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 用户个人 key-value 配置：userId 一律取自登录态，接口不接受任何形式的 userId 入参
 */
@Tag(name = "用户个人配置", description = "用户个人 key-value 配置，仅本人可读写")
@RestController
@RequestMapping("/api/user/config")
public class UserConfigController {

    @Resource
    private UserConfigApplicationService userConfigApplicationService;

    @Operation(summary = "查询本人全部配置项（含备注与时间戳）")
    @GetMapping("/list")
    public ReqResult<List<UserConfigItemDto>> list() {
        return ReqResult.success(userConfigApplicationService.queryConfigList(RequestContext.get().getUserId()));
    }

    @Operation(summary = "查询本人全部配置（仅 key→value）")
    @GetMapping("/map")
    public ReqResult<Map<String, Object>> map() {
        return ReqResult.success(userConfigApplicationService.queryConfigMap(RequestContext.get().getUserId()));
    }

    @Operation(summary = "查询本人单个配置项，不存在时 data 为 null")
    @GetMapping("/get")
    public ReqResult<Object> get(@RequestParam(required = false) String key) {
        return ReqResult.success(userConfigApplicationService.queryConfig(RequestContext.get().getUserId(), key));
    }

    @Operation(summary = "写入本人单个配置项（按 key 原子覆盖）")
    @PostMapping("/set")
    public ReqResult<Void> set(@RequestBody UserConfigSetDto userConfigSetDto) {
        userConfigApplicationService.setConfig(RequestContext.get().getUserId(),
                userConfigSetDto.getKey(), userConfigSetDto.getValue(), userConfigSetDto.getDescription());
        return ReqResult.success();
    }

    @Operation(summary = "删除本人单个配置项（幂等）")
    @PostMapping("/delete")
    public ReqResult<Void> delete(@RequestParam(required = false) String key) {
        userConfigApplicationService.deleteConfig(RequestContext.get().getUserId(), key);
        return ReqResult.success();
    }
}
