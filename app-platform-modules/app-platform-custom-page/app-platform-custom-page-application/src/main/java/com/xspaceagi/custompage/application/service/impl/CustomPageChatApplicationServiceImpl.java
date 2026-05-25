package com.xspaceagi.custompage.application.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.xspaceagi.custompage.application.service.ICustomPageChatApplicationService;
import com.xspaceagi.custompage.domain.model.CustomPageConversationModel;
import com.xspaceagi.custompage.domain.service.ICustomPageChatDomainService;
import com.xspaceagi.custompage.domain.service.ICustomPageChatFluxService;
import com.xspaceagi.custompage.domain.service.ICustomPageConversationDomainService;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.page.SuperPage;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

@Slf4j
@Service
public class CustomPageChatApplicationServiceImpl implements ICustomPageChatApplicationService {

    @Resource
    private ICustomPageChatDomainService customPageChatDomainService;
    @Resource
    private ICustomPageChatFluxService customPageChatFluxService;
    @Resource
    private ICustomPageConversationDomainService customPageConversationDomainService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReqResult<Void> saveConversation(CustomPageConversationModel model, UserContext userContext) {
        log.info("[Application] project Id={},savesession records", model.getProjectId());

        ReqResult<Long> domainResult = customPageConversationDomainService.saveConversation(model, userContext);

        if (!domainResult.isSuccess()) {
            log.error("[Application] project Id={},savesession records failed,error={}", model.getProjectId(),
                    domainResult.getMessage());
            return ReqResult.error(domainResult.getCode(), domainResult.getMessage());
        }

        log.info("[Application] project Id={},savesession records succeeded,result={}", model.getProjectId(), domainResult.getData());
        return ReqResult.success();
    }

    @Override
    public ReqResult<List<CustomPageConversationModel>> listConversations(Long projectId, UserContext userContext) {
        log.info("[Application] project Id={},queryusersession records", projectId);

        List<CustomPageConversationModel> modelList = new ArrayList<>();
        List<CustomPageConversationModel> models = customPageConversationDomainService
                .listByProjectId(projectId, userContext.getUserId());
        if (models != null && !models.isEmpty()) {
            modelList.addAll(models);
        }

        log.info("[Application] project Id={},queryusersession recordsreturn size={}", projectId, modelList.size());
        return ReqResult.success(modelList);
    }

    @Override
    public ReqResult<SuperPage<CustomPageConversationModel>> pageQueryConversations(
            CustomPageConversationModel queryModel, Long current, Long pageSize, UserContext userContext) {
        log.info("[Application] project Id={},pagedqueryusersession records, current={}, page Size={}", queryModel.getProjectId(), current,
                pageSize);

        ReqResult<SuperPage<CustomPageConversationModel>> domainResult = customPageConversationDomainService
                .pageQuery(queryModel, current, pageSize, userContext);

        if (!domainResult.isSuccess()) {
            log.error("[Application] project Id={},pagedqueryusersession records failed,error={}", queryModel.getProjectId(),
                    domainResult.getMessage());
            return ReqResult.error(domainResult.getCode(), domainResult.getMessage());
        }

        log.info("[Application] project Id={},pagedqueryusersession records succeeded,total={}", queryModel.getProjectId(),
                domainResult.getData().getTotal());
        return domainResult;
    }

    @Override
    public Flux<Map<String, Object>> sendAgentChatFlux(Map<String, Object> chatBody,
            UserContext userContext) {
        log.info("[Application] send chat message(Fluxreactive),chat Body keys={}", chatBody == null ? null : chatBody.keySet());
        Optional.ofNullable(chatBody).orElseThrow(() -> new IllegalArgumentException("Request body cannot be empty"));

        return customPageChatFluxService.sendAgentChatFlux(chatBody, userContext);
    }

    @Override
    public ReqResult<Void> terminateChatSession(String sessionId, UserContext userContext) {
        log.info("[Application] terminatesessionrequest: session Id={}", sessionId);
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return ReqResult.error("0001", "sessionId is required");
        }

        customPageChatFluxService.terminateSession(sessionId);
        return ReqResult.success();
    }

    @Override
    public SseEmitter startAgentSessionSse(String sessionId, Long projectId, String requestId,
            UserContext userContext) {
        log.info("[Application] establish session SSE, session Id={}, request Id={}", sessionId, requestId);
        return customPageChatDomainService.startAgentSessionSse(sessionId, projectId, requestId, userContext);
    }

    @Override
    public ReqResult<Map<String, Object>> agentSessionCancel(String projectId, String sessionId,
            UserContext userContext) {
        log.info("[Application] project Id={},cancel agent task,session Id={}", projectId, sessionId);
        return customPageChatDomainService.agentSessionCancel(projectId, sessionId, userContext);
    }

    @Override
    public ReqResult<Map<String, Object>> getAgentStatus(String projectId, UserContext userContext) {
        log.info("[Application] project Id={},query Agentstatus", projectId);
        return customPageChatDomainService.getAgentStatus(projectId, userContext);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReqResult<Map<String, Object>> stopAgent(String projectId, UserContext userContext) {
        log.info("[Application] project Id={},stop Agentservice", projectId);
        return customPageChatDomainService.stopAgent(projectId, userContext);
    }
}
