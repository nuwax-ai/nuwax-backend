package com.xspaceagi.custompage.domain.keepalive;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xspaceagi.custompage.domain.gateway.PageFileBuildClient;
import com.xspaceagi.custompage.domain.model.CustomPageBuildModel;
import com.xspaceagi.custompage.domain.proxypath.ICustomPageProxyPathService;
import com.xspaceagi.custompage.domain.repository.ICustomPageBuildRepository;
import com.xspaceagi.custompage.domain.service.ICustomPageConfigDomainService;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.utils.RedisUtil;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 保活服务
 */
@Slf4j
@Service
public class KeepAliveServiceImpl implements IKeepAliveService {

    @Resource
    private RedisUtil redisUtil;
    @Resource
    private PageFileBuildClient pageFileBuildClient;
    @Resource
    private ICustomPageBuildRepository customPageBuildRepository;
    @Resource
    private ICustomPageConfigDomainService customPageConfigDomainService;
    @Resource
    private ICustomPageProxyPathService customPageProxyPathService;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // Redis key前缀
    private static final String KEEPALIVE_KEY_PREFIX = "dev:keepalive:";

    // 所有保活项目ID集合的key
    private static final String KEEPALIVE_PROJECTS_SET_KEY = "dev:keepalive:projects";

    /**
     * 处理保活请求
     */
    public ReqResult<Map<String, Object>> handleKeepAlive(Long projectId, UserContext userContext) {
        log.info("[Keep Alive] project Id={},starthandlekeep-aliverequest", projectId);

        try {
            CustomPageBuildModel project = customPageBuildRepository.getByProjectId(projectId);
            if (project == null) {
                log.info("[Keep Alive] project Id={},projectnot found", projectId);
                return ReqResult.error("0001", "Project does not exist");
            }

            String devProxyPath = customPageProxyPathService.getDevProxyPath(projectId);
            Map<String, Object> serverResp = null;

            // 需要先判断表中的状态,如果直接请求server启动dev,有可能server重启过,丢失了缓存,但dev还活着,再次请求就会多开dev服务器
            if (project.getDevPid() != null && project.getDevPort() != null) {
                // 调server保活
                log.info("[Keep Alive] project Id={},dev is running from table, call server keep-alive API", projectId);
                serverResp = pageFileBuildClient.keepAlive(projectId, devProxyPath, project.getDevPid(),
                        project.getDevPort());
            } else {
                // 调server启动dev
                log.info("[Keep Alive] project Id={},dev not running, call server start dev", projectId);
                serverResp = pageFileBuildClient.startDev(projectId, devProxyPath);

            }
            if (serverResp == null) {
                log.info("[Keep Alive] project Id={},keep-alivefailed,server returned null", projectId);
                updateKeepAlive(projectId, new Date(), YesOrNoEnum.N.getKey(), null, null, userContext);
                return ReqResult.error("9999", "Keep-alive failed: server returned no response");
            }

            boolean success = Boolean.parseBoolean(String.valueOf(serverResp.get("success")));
            String message = serverResp.get("message") == null ? "" : String.valueOf(serverResp.get("message"));
            if (!success) {
                log.error("[Keep Alive] project Id={},keep-alivefailed,serverreturned error,code={},message={}", projectId,
                        serverResp.get("code"), message);
                updateKeepAlive(projectId, new Date(), YesOrNoEnum.N.getKey(), null, null, userContext);

                String code = serverResp.get("code") == null ? "" : String.valueOf(serverResp.get("code"));
                if ("PROJECT_STARTING".equals(code)) {
                    return ReqResult.error(ErrorCodeEnum.PROJECT_STARTING.getCode(), ErrorCodeEnum.PROJECT_STARTING.getMsg());
                }
                return ReqResult.error("9999", message);
            }

            // 持久化 dev 运行信息
            Integer pid = null;
            Integer port = null;
            try {
                Object pidObj = serverResp.get("pid");
                Object portObj = serverResp.get("port");
                pid = Integer.valueOf(String.valueOf(pidObj));
                port = Integer.valueOf(String.valueOf(portObj));
            } catch (Exception e) {
                log.error("[Keep Alive] project Id={},get dev port and pid exception", projectId, e);
                updateKeepAlive(projectId, new Date(), YesOrNoEnum.N.getKey(), null, null, userContext);
                return ReqResult.error("9999", "Failed to obtain service port and process ID: " + e.getMessage());
            }
            updateKeepAlive(projectId, new Date(), YesOrNoEnum.Y.getKey(), pid, port, userContext);

            // 返回结果
            Map<String, Object> result = new HashMap<>();
            result.put("devRunning", YesOrNoEnum.Y.getKey());
            result.put("port", port);
            return ReqResult.success(result);
        } catch (Exception e) {
            log.error("[Keep Alive] project Id={},keep-alivehandleexception", projectId, e);
            return ReqResult.error("9999", "Keep-alive error: " + e.getMessage());
        }
    }

    /**
     * 更新保活信息
     */
    public void updateKeepAlive(Long projectId,
            Date keepAliveTime,
            Integer devRunning,
            Integer devPid,
            Integer devPort,
            UserContext userContext) {
        CustomPageBuildModel keepAliveModel = new CustomPageBuildModel();
        keepAliveModel.setProjectId(projectId);
        keepAliveModel.setLastKeepAliveTime(keepAliveTime);
        keepAliveModel.setDevRunning(devRunning);
        keepAliveModel.setDevPid(devPid);
        keepAliveModel.setDevPort(devPort);
        keepAliveModel.setTenantId(userContext.getTenantId());

        // 更新Redis缓存
        updateKeepAliveCache(projectId, keepAliveModel);

        // 更新数据库
        updatKeepAliveDb(keepAliveModel, userContext);
    }

    /**
     * 更新缓存
     */
    private void updateKeepAliveCache(Long projectId, CustomPageBuildModel model) {
        try {
            String key = KEEPALIVE_KEY_PREFIX + projectId;
            String value = objectMapper.writeValueAsString(model);

            // 不设置过期时间，手动管理生命周期
            redisUtil.set(key, value);

            // 将projectId添加到保活项目集合中
            redisUtil.sSet(KEEPALIVE_PROJECTS_SET_KEY, projectId.toString());

            log.info("[Keep Alive] project Id={},cache updated", projectId);
        } catch (Exception e) {
            log.error("[Keep Alive] project Id={},cacheupdatefailed", projectId, e);
        }
    }

    /**
     * 更新库表
     */
    private void updatKeepAliveDb(CustomPageBuildModel model, UserContext userContext) {
        try {
            customPageBuildRepository.updateKeepAlive(model, userContext);
            log.info("[Keep Alive] project Id={},db table updated", model.getProjectId());
        } catch (Exception e) {
            log.error("[Keep Alive] project Id={},db table update failed", model.getProjectId(), e);
            throw BizException.of(ErrorCodeEnum.SYS_ERROR, BizExceptionCodeEnum.customPageKeepAliveDbUpdateFailed);
        }
    }

    /**
     * 删除保活缓存
     */
    @Override
    public void removeKeepAliveCache(Long projectId) {
        redisUtil.expire(KEEPALIVE_KEY_PREFIX + projectId, 0);

        redisUtil.remove(KEEPALIVE_PROJECTS_SET_KEY, projectId.toString());

        log.info("[Keep Alive] project Id={},deleterediskeep-alivecachecompleted", projectId);
    }
}