package com.xspaceagi.custompage.application.service.impl;

import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.xspaceagi.custompage.application.service.ICustomPageBuildApplicationService;
import com.xspaceagi.custompage.domain.model.CustomPageBuildModel;
import com.xspaceagi.custompage.domain.service.ICustomPageBuildDomainService;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class CustomPageBuildApplicationServiceImpl implements ICustomPageBuildApplicationService {

    @Resource
    private ICustomPageBuildDomainService customPageBuildDomainService;

    // 不需要事务
    // @Transactional(rollbackFor = Exception.class, timeout = 30)
    @Override
    public ReqResult<Map<String, Object>> startDev(Long projectId, UserContext userContext) {
        log.info("[Application] project Id={},start dev server", projectId);

        ReqResult<Map<String, Object>> result = customPageBuildDomainService.startDev(projectId, userContext);

        log.info("[Application] project Id={},start dev response,result={}", projectId, result);
        return result;
    }

    // 需要开启事务
    @Transactional(rollbackFor = Exception.class, timeout = 60)
    @Override
    public ReqResult<Map<String, Object>> build(Long projectId, String publishType, UserContext userContext) {
        log.info("[Application] project Id={},build and publish frontend project", projectId);

        ReqResult<Map<String, Object>> result = customPageBuildDomainService.build(projectId, publishType, userContext);

        log.info("[Application] project Id={},build and publish frontend projectresponse,result={}", projectId, result);
        return result;
    }

    // 不需要事务
    // @Transactional(rollbackFor = Exception.class, timeout = 30)
    @Override
    public ReqResult<Map<String, Object>> stopDev(Long projectId, UserContext userContext) {
        log.info("[Application] project Id={},stop dev server", projectId);

        ReqResult<Map<String, Object>> result = customPageBuildDomainService.stopDev(projectId, userContext);

        log.info("[Application] project Id={},stop dev serverresponse,result={}", projectId, result);
        return result;
    }

    // 不需要事务
    // @Transactional(rollbackFor = Exception.class, timeout = 30)
    @Override
    public ReqResult<Map<String, Object>> restartDev(Long projectId, UserContext userContext) {
        log.info("[Application] project Id={},restart dev server", projectId);

        ReqResult<Map<String, Object>> result = customPageBuildDomainService.restartDev(projectId, userContext);

        log.info("[Application] project Id={},restart dev serverresponse,result={}", projectId, result);
        return result;
    }

    // 不需要事务
    // @Transactional(rollbackFor = Exception.class, timeout = 10)
    @Override
    public ReqResult<Map<String, Object>> keepAlive(Long projectId, UserContext userContext) {
        log.debug("[keep Alive] project Id={},keep-alivehandle", projectId);

        ReqResult<Map<String, Object>> result = customPageBuildDomainService.keepAlive(projectId, userContext);

        log.info("[keep Alive] project Id={},keep-alivehandleresponse,result={}", projectId, result);
        return result;
    }

    @Override
    public CustomPageBuildModel getByProjectId(Long id) {
        return customPageBuildDomainService.getByProjectId(id);
    }

    @Override
    public ReqResult<Map<String, Object>> initProject(Long projectId, UserContext userContext) {
        log.info("[init Project] project Id={},startinitializeproject artifacts", projectId);

        ReqResult<Map<String, Object>> result = customPageBuildDomainService.initProject(projectId);

        log.info("[init Project] project Id={},startinitializeprojectresponse,result={}", projectId, result);
        return result;
    }

    @Override
    public ReqResult<Map<String, Object>> deleteProjectFiles(CustomPageBuildModel model, UserContext userContext) {
        log.info("[delete Project Files] project Id={},startdelete project files", model.getProjectId());

        ReqResult<Map<String, Object>> result = customPageBuildDomainService.deleteProjectFiles(model, userContext);

        log.info("[delete Project Files] project Id={},delete project filesresponse,result={}", model.getProjectId(), result);
        return result;
    }

    @Override
    public ReqResult<Map<String, Object>> getDevLog(Long projectId, Integer startIndex, String logType, UserContext userContext) {
        log.debug("[get Dev Log] project Id={}, start Index={}, startquery logs", projectId, startIndex);

        ReqResult<Map<String, Object>> result = customPageBuildDomainService.getDevLog(projectId, startIndex, logType, userContext);

        log.debug("[get Dev Log] project Id={}, query logsresponse, code={}", projectId, result.getCode());
        return result;
    }

    @Override
    public ReqResult<Map<String, Object>> copyProject(Long sourceProjectId, Long targetProjectId, UserContext userContext) {
        log.info("[copy Project] source Project Id={},target Project Id={},startcopyproject artifacts", sourceProjectId, targetProjectId);

        ReqResult<Map<String, Object>> result = customPageBuildDomainService.copyProject(sourceProjectId, targetProjectId);

        log.info("[copy Project] source Project Id={},target Project Id={},copyproject artifactsresponse,result={}", sourceProjectId, targetProjectId, result);
        return result;
    }

    @Override
    public ReqResult<Map<String, Object>> getLogCacheStats() {
        log.info("[Application] getlogcachestats");

        ReqResult<Map<String, Object>> result = customPageBuildDomainService.getLogCacheStats();

        log.info("[Application] getlogcachestatsresponse, result={}", result);
        return result;
    }

    @Override
    public ReqResult<Map<String, Object>> clearAllLogCache() {
        log.info("[Application] clear all log cache");

        ReqResult<Map<String, Object>> result = customPageBuildDomainService.clearAllLogCache();

        log.info("[Application] clear all log cacheresponse, result={}", result);
        return result;
    }
}