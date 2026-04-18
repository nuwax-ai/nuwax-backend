package com.xspaceagi.custompage.ui.web.controller;

import com.xspaceagi.custompage.application.service.ICustomPageBuildApplicationService;
import com.xspaceagi.custompage.domain.proxypath.ICustomPageProxyPathService;
import com.xspaceagi.custompage.ui.web.dto.*;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.exception.SpacePermissionException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import static com.xspaceagi.system.spec.enums.ResourceEnum.*;

@Tag(name = "Web app", description = "Custom page web app APIs")
@RestController
@RequestMapping("/api/custom-page")
@Slf4j
@RequiredArgsConstructor
public class CustomPageBuildController extends BaseController {

    @Resource
    private ICustomPageBuildApplicationService customPageBuildApplicationService;
    @Resource
    private ICustomPageProxyPathService customPageProxyPathApplicationService;

    @RequireResource(PAGE_APP_RESTART_SERVER)
    @Operation(summary = "Start dev server", description = "Start the frontend dev server")
    @PostMapping(value = "/start-dev", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<CustomBuildRes> startDev(@RequestBody CustomBuildReq req) {
        log.info("[Web] startfrontend servicerequest, project Id={}", req.getProjectId());
        try {
            UserContext userContext = getUser();

            ReqResult<Map<String, Object>> result = customPageBuildApplicationService.startDev(req.getProjectId(),
                    userContext);
            log.info("[Web] startfrontend servicecompleted, status Code={}", result.getCode());

            if (!result.isSuccess()) {
                return ReqResult.create(result.getCode(), result.getMessage(), null);
            }

            CustomBuildRes res = new CustomBuildRes();
            res.setProjectId(req.getProjectId());
            res.setProjectIdStr(String.valueOf(req.getProjectId()));

            Map<String, Object> data = result.getData();
            if (data != null) {
                Object portObj = data.get("port");
                if (portObj != null) {
                    res.setDevServerUrl(customPageProxyPathApplicationService.getDevProxyPath(req.getProjectId()));
                }
            }
            return ReqResult.success(res);
        } catch (SpacePermissionException e) {
            log.error("[Web] startfrontend servicefailed, project Id={}, {}", req.getProjectId(), e.getMessage());
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[Web] startfrontend servicefailed, project Id={}", req.getProjectId(), e);
            return ReqResult.error("0001", "Failed to start frontend dev server: " + e.getMessage());
        }
    }

    @RequireResource(PAGE_APP_PUBLISH)
    @Operation(summary = "Build and publish", description = "Build and publish the frontend project")
    @PostMapping(value = "/build", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<CustomBuildRes> build(@RequestBody CustomBuildReq req) {
        log.info("[Web] build releaserequest, project Id={}", req.getProjectId());
        try {
            UserContext userContext = getUser();

            ReqResult<Map<String, Object>> result = customPageBuildApplicationService.build(req.getProjectId(),
                    req.getPublishType(), userContext);
            log.info("[Web] build releasecompleted, status Code={}", result.getCode());

            if (!result.isSuccess()) {
                return ReqResult.create(result.getCode(), result.getMessage(), null);
            }

            CustomBuildRes res = new CustomBuildRes();
            res.setProjectId(req.getProjectId());
            res.setProjectIdStr(String.valueOf(req.getProjectId()));
            res.setProdServerUrl(customPageProxyPathApplicationService.getProdProxyPath(req.getProjectId()));

            return ReqResult.success(res);
        } catch (SpacePermissionException e) {
            log.error("[Web] build releasefailed, project Id={}, {}", req.getProjectId(), e.getMessage());
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[Web] build releasefailed, project Id={}", req.getProjectId(), e);
            return ReqResult.error("0001", "Build and publish failed: " + e.getMessage());
        }
    }

    @RequireResource(PAGE_APP_RESTART_SERVER)
    @Operation(summary = "Stop dev server", description = "Stop the frontend dev server")
    @PostMapping(value = "/stop-dev", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<CustomBuildRes> stopDev(@RequestBody CustomBuildReq req) {
        log.info("[Web] stop dev serverrequest, project Name={}", req.getProjectId());
        try {
            UserContext userContext = getUser();

            ReqResult<Map<String, Object>> result = customPageBuildApplicationService.stopDev(req.getProjectId(),
                    userContext);
            log.info("[Web] stop dev servercompleted, status Code={}", result.getCode());

            if (!result.isSuccess()) {
                return ReqResult.create(result.getCode(), result.getMessage(), null);
            }

            CustomBuildRes res = new CustomBuildRes();
            res.setProjectId(req.getProjectId());
            res.setProjectIdStr(String.valueOf(req.getProjectId()));
            return ReqResult.success(res);
        } catch (SpacePermissionException e) {
            log.error("[Web] stop dev serverfailed, project Name={}, {}", req.getProjectId(), e.getMessage());
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[Web] stop dev serverfailed, project Name={}", req.getProjectId(), e);
            return ReqResult.error("0001", "Failed to stop dev server: " + e.getMessage());
        }
    }

    @RequireResource(PAGE_APP_RESTART_SERVER)
    @Operation(summary = "Restart dev server", description = "Restart the frontend dev server")
    @PostMapping(value = "/restart-dev", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<CustomBuildRes> restartDev(@RequestBody CustomBuildReq req) {
        log.info("[Web] restart dev serverrequest, project Id={}", req.getProjectId());
        try {
            UserContext userContext = getUser();

            ReqResult<Map<String, Object>> result = customPageBuildApplicationService.restartDev(req.getProjectId(),
                    userContext);
            log.info("[Web] restart dev servercompleted, status Code={}", result.getCode());

            if (!result.isSuccess()) {
                return ReqResult.create(result.getCode(), result.getMessage(), null);
            }

            CustomBuildRes res = new CustomBuildRes();
            res.setProjectId(req.getProjectId());
            res.setProjectIdStr(String.valueOf(req.getProjectId()));

            Map<String, Object> data = result.getData();
            if (data != null) {
                Object portObj = data.get("port");
                if (portObj != null) {
                    res.setDevServerUrl(customPageProxyPathApplicationService.getDevProxyPath(req.getProjectId()));
                }
            }
            return ReqResult.success(res);
        } catch (SpacePermissionException e) {
            log.error("[Web] restart dev serverfailed, project Id={}, {}", req.getProjectId(), e.getMessage());
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[Web] restart dev serverfailed, project Id={}", req.getProjectId(), e);
            return ReqResult.error("0001", "Failed to restart dev server: " + e.getMessage());
        }
    }

    @RequireResource(PAGE_APP_QUERY_DETAIL)
    @Operation(summary = "Dev server keep-alive", description = "Periodic keep-alive; dev server stops if no requests within timeout")
    @PostMapping(value = "/keepalive", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<KeepAliveRes> keepAlive(@RequestBody KeepAliveReq req) {
        log.debug("[Web] project Id={},receivedkeep-aliverequest", req.getProjectId());
        try {
            UserContext userContext = getUser();

            ReqResult<Map<String, Object>> result = customPageBuildApplicationService.keepAlive(req.getProjectId(), userContext);
            log.debug("[Web] project Id={},keep-alivehandlecompleted, status Code={}", req.getProjectId(), result.getCode());

            if (!result.isSuccess()) {
                return ReqResult.create(result.getCode(), result.getMessage(), null);
            }

            KeepAliveRes res = new KeepAliveRes();
            res.setProjectId(req.getProjectId());
            res.setProjectIdStr(String.valueOf(req.getProjectId()));

            Map<String, Object> data = result.getData();
            if (data != null) {
                Object portObj = data.get("port");
                if (portObj != null) {
                    res.setDevServerUrl(customPageProxyPathApplicationService.getDevProxyPath(req.getProjectId()));
                }
            }
            return ReqResult.success(res);
        } catch (SpacePermissionException e) {
            log.error("[Web] project Id={},keep-alivehandlefailed, {}", req.getProjectId(), e.getMessage());
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[Web] project Id={},keep-alivehandleexception", req.getProjectId(), e);
            return ReqResult.error("0001", e.getMessage());
        }
    }

    @RequireResource(PAGE_APP_QUERY_DETAIL)
    @Operation(summary = "Get dev server logs", description = "Query frontend dev server logs")
    @PostMapping(value = "/get-dev-log", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<GetDevLogRes> getDevLog(@RequestBody GetDevLogReq req) {
        log.debug("[get Dev Log] project Id={}, request, start Index={},log Type={}", req.getProjectId(), req.getStartIndex(), req.getLogType());
        try {
            UserContext userContext = getUser();

            if (req.getLogType() == null) {
                req.setLogType("temp");
            }
            ReqResult<Map<String, Object>> result = customPageBuildApplicationService.getDevLog(req.getProjectId(),
                    req.getStartIndex(), req.getLogType(), userContext);
            log.debug("[get Dev Log] querycompleted, status Code={}", result.getCode());

            if (!result.isSuccess()) {
                return ReqResult.create(result.getCode(), result.getMessage(), null);
            }

            GetDevLogRes res = new GetDevLogRes();
            res.setProjectId(req.getProjectId());
            res.setStartIndex(req.getStartIndex());

            Map<String, Object> data = result.getData();
            if (data != null) {
                Object logsObj = data.get("logs");
                Object totalLinesObj = data.get("totalLines");
                Object cacheHitObj = data.get("cacheHit");
                Object fileTooLargeObj = data.get("fileTooLarge");
                if (logsObj != null) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> logsList = (List<Map<String, Object>>) logsObj;
                    res.setLogs(logsList);
                }
                if (totalLinesObj != null) {
                    res.setTotalLines((Integer) totalLinesObj);
                }
                if (cacheHitObj != null) {
                    res.setCacheHit(Boolean.TRUE.equals(cacheHitObj));
                }
                if (fileTooLargeObj != null) {
                    res.setFileTooLarge(Boolean.TRUE.equals(fileTooLargeObj));
                }
                res.setLogFileName(data.get("logFileName") == null ? null : data.get("logFileName").toString());
            }
            return ReqResult.success(res);
        } catch (SpacePermissionException e) {
            log.error("[get Dev Log] project Id={}, query logsfailed, {}", req.getProjectId(), e.getMessage());
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[get Dev Log] project Id={}, query logsfailed", req.getProjectId(), e);
            return ReqResult.error("0001", "Query logs failed: " + e.getMessage());
        }
    }

    @RequireResource(PAGE_APP_QUERY_LIST)
    @Operation(summary = "Get log cache statistics", description = "Statistics for dev log caches across projects")
    @GetMapping(value = "/get-log-cache-stats", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Map<String, Object>> getLogCacheStats() {
        log.info("[Web] getlogcachestatsrequest");
        try {
            ReqResult<Map<String, Object>> result = customPageBuildApplicationService.getLogCacheStats();
            log.info("[Web] getlogcachestatscompleted, status Code={}", result.getCode());

            if (!result.isSuccess()) {
                return ReqResult.create(result.getCode(), result.getMessage(), null);
            }

            return ReqResult.success(result.getData());
        } catch (Exception e) {
            log.error("[Web] getlogcachestatsfailed", e);
            return ReqResult.error("0001", "Failed to get log cache statistics: " + e.getMessage());
        }
    }

    @RequireResource(PAGE_APP_QUERY_LIST)
    @Operation(summary = "Clear log cache", description = "Clear dev log caches for all projects")
    @GetMapping(value = "/clear-all-log-cache", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Map<String, Object>> clearAllLogCache() {
        log.info("[Web] clearlogcacherequest");
        try {
            ReqResult<Map<String, Object>> result = customPageBuildApplicationService.clearAllLogCache();
            log.info("[Web] clearlogcachecompleted, status Code={}", result.getCode());

            if (!result.isSuccess()) {
                return ReqResult.create(result.getCode(), result.getMessage(), null);
            }

            return ReqResult.success(result.getData());
        } catch (Exception e) {
            log.error("[Web] clearlogcachefailed", e);
            return ReqResult.error("0001", "Failed to clear log cache: " + e.getMessage());
        }
    }

}