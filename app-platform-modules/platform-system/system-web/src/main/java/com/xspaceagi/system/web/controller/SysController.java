package com.xspaceagi.system.web.controller;

import com.xspaceagi.agent.core.adapter.application.FileServerVersionApplicationService;
import com.xspaceagi.agent.core.adapter.dto.FileServerVersionDto;
import com.xspaceagi.system.application.service.SysApplicationService;
import com.xspaceagi.system.application.service.SysUserPermissionCacheService;
import com.xspaceagi.system.spec.dto.ReqResult;
import jakarta.annotation.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

@RestController
public class SysController {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Resource
    private SysApplicationService sysApplicationService;
    @Resource
    private SysUserPermissionCacheService sysUserPermissionCacheService;
    @Resource
    private FileServerVersionApplicationService fileServerVersionApplicationService;

    /**
     * 获取系统启动时间，排查问题使用（匿名可访问）
     */
    @GetMapping(value = "/api/sys/v", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<String> startTime() {
        LocalDateTime serverStartupTime = sysApplicationService.getServerStartupTime();
        return ReqResult.success(serverStartupTime != null ? serverStartupTime.format(FORMAT) : null);
    }

    /**
     * 获取 file-server 版本，排查问题使用（走 /api/system，需管理员）
     * 不传 cId：当前租户全局沙箱上的 file-server 列表；
     * 传 cId：该会话绑定沙箱上的 file-server。
     */
    @GetMapping(value = "/api/system/file-server-version", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<List<FileServerVersionDto>> fileServerVersion(@RequestParam(value = "cId", required = false) Long cId) {
        if (cId != null) {
            return ReqResult.success(Collections.singletonList(fileServerVersionApplicationService.getByConversation(cId)));
        }
        return ReqResult.success(fileServerVersionApplicationService.listCurrentTenantVersions());
    }

    /**
     * 权限缓存时间，排查问题使用（走 /api/system，需管理员）
     */
    @GetMapping(value = "/api/system/permission-cache-time", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<String> permissionCacheTime() {
        Long timestamp = sysUserPermissionCacheService.getPermissionLatestCacheTime();
        if (timestamp == null) {
            return ReqResult.success(null);
        }
        LocalDateTime dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault());
        return ReqResult.success(dateTime.format(FORMAT));
    }

}
