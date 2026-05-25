package com.xspaceagi.custompage.domain.service.impl;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.xspaceagi.custompage.domain.model.CustomPageConversationModel;
import com.xspaceagi.custompage.domain.repository.ICustomPageConfigRepository;
import com.xspaceagi.custompage.domain.repository.ICustomPageConversationRepository;
import com.xspaceagi.custompage.domain.service.ICustomPageConversationDomainService;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.page.SuperPage;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class CustomPageConversationDomainServiceImpl implements ICustomPageConversationDomainService {

    @Resource
    private ICustomPageConversationRepository customPageConversationRepository;
    @Resource
    private ICustomPageConfigRepository customPageConfigRepository;
    @Resource
    private SpacePermissionService spacePermissionService;

    @Override
    public ReqResult<Long> saveOrUpdateAssistantConversation(CustomPageConversationModel model, UserContext userContext) {
        Optional.ofNullable(model.getProjectId()).filter(x -> x > 0)
                .orElseThrow(() -> new IllegalArgumentException("projectId is required or invalid"));
        Optional.ofNullable(model.getContent()).filter(x -> !x.trim().isEmpty())
                .orElseThrow(() -> new IllegalArgumentException("content is required"));
        Optional.ofNullable(model.getSessionId()).filter(x -> !x.trim().isEmpty())
                .orElseThrow(() -> new IllegalArgumentException("sessionId is required"));

        try {
            var configModel = customPageConfigRepository.getById(model.getProjectId());
            if (configModel == null) {
                return ReqResult.error("0001", "Project does not exist");
            }
            spacePermissionService.checkSpaceUserPermission(configModel.getSpaceId());
            model.setSpaceId(configModel.getSpaceId());

            if (model.getId() != null) {
                CustomPageConversationModel existing = customPageConversationRepository.findById(model.getId());
                if (existing == null || !"ASSISTANT".equalsIgnoreCase(existing.getRole())
                        || !model.getProjectId().equals(existing.getProjectId())) {
                    return ReqResult.error("0001", "Invalid assistant conversation id");
                }
                boolean updated = customPageConversationRepository.updateAssistantContent(model.getId(),
                        model.getContent(), model.getRequestId(), userContext);
                return updated ? ReqResult.success(model.getId())
                        : ReqResult.error("0001", "Failed to update assistant conversation");
            }
            Long id = customPageConversationRepository.save(model, userContext);
            return ReqResult.success(id);
        } catch (Exception e) {
            log.error("[Domain] save or update assistant conversation failed, project Id={}, sessionId={}",
                    model.getProjectId(), model.getSessionId(), e);
            return ReqResult.error("0001", "Failed to save assistant conversation: " + e.getMessage());
        }
    }

    @Override
    public ReqResult<Long> saveConversation(CustomPageConversationModel model, UserContext userContext) {
        log.info("[Domain] saveusersession records, project Id={}, topic={}", model.getProjectId(), model.getTopic());

        Optional.ofNullable(model.getProjectId()).filter(x -> x > 0)
                .orElseThrow(() -> new IllegalArgumentException("projectId is required or invalid"));
        Optional.ofNullable(model.getContent()).filter(x -> !x.trim().isEmpty())
                .orElseThrow(() -> new IllegalArgumentException("content is required"));

        try {
            var configModel = customPageConfigRepository.getById(model.getProjectId());
            if (configModel == null) {
                log.warn("[Domain] Project not found, project Id={}", model.getProjectId());
                return ReqResult.error("0001", "Project does not exist");
            }

            // 校验空间权限
            spacePermissionService.checkSpaceUserPermission(configModel.getSpaceId());

            // 设置 spaceId
            model.setSpaceId(configModel.getSpaceId());

            Long id = customPageConversationRepository.save(model, userContext);
            log.info("[Domain] saveusersession records succeeded, id={}", id);

            return ReqResult.success(id);
        } catch (Exception e) {
            log.error("[Domain] saveusersession recordsexception, project Id={}", model.getProjectId(), e);
            return ReqResult.error("0001", "Failed to save conversation: " + e.getMessage());
        }
    }

    @Override
    public List<CustomPageConversationModel> listByProjectId(Long projectId, Long userId) {
        log.info("[Domain] queryprojectsession recordslist, project Id={}, user Id={}", projectId, userId);

        List<CustomPageConversationModel> models = customPageConversationRepository
                .listByProjectId(projectId, userId);
        if (models != null && !models.isEmpty()) {
            // 校验空间权限
            spacePermissionService.checkSpaceUserPermission(models.get(0).getSpaceId());
        }

        return models;
    }

    @Override
    public ReqResult<SuperPage<CustomPageConversationModel>> pageQuery(CustomPageConversationModel queryModel,
            Long current, Long pageSize, UserContext userContext) {
        log.info("[Domain] pagedquerysession records, project Id={}, current={}, page Size={}", queryModel.getProjectId(), current,
                pageSize);

        try {
            Optional.ofNullable(queryModel.getProjectId()).filter(x -> x > 0)
                    .orElseThrow(() -> new IllegalArgumentException("projectId is required or invalid"));
            Optional.ofNullable(current).filter(x -> x > 0)
                    .orElseThrow(() -> new IllegalArgumentException("current is required or invalid"));
            Optional.ofNullable(pageSize).filter(x -> x > 0)
                    .orElseThrow(() -> new IllegalArgumentException("pageSize is required or invalid"));

            // 校验项目是否存在并获取空间权限
            var configModel = customPageConfigRepository.getById(queryModel.getProjectId());
            if (configModel == null) {
                log.warn("[Domain] Project not found, project Id={}", queryModel.getProjectId());
                return ReqResult.error("0001", "Project does not exist");
            }

            // 校验空间权限
            spacePermissionService.checkSpaceUserPermission(configModel.getSpaceId());

            SuperPage<CustomPageConversationModel> result = customPageConversationRepository.pageQuery(queryModel,
                    current, pageSize);
            log.info("[Domain] pagedquerysession records succeeded, total={}", result.getTotal());

            return ReqResult.success(result);
        } catch (Exception e) {
            log.error("[Domain] pagedquerysession recordsexception, project Id={}", queryModel.getProjectId(), e);
            return ReqResult.error("0001", "Failed to page query conversations: " + e.getMessage());
        }
    }

    @Override
    public ReqResult<Void> updateUserSessionIdByRequestId(Long projectId, String requestId, String sessionId,
                                                           UserContext userContext) {
        try {
            if (projectId == null || projectId <= 0 || requestId == null || requestId.isBlank()
                    || sessionId == null || sessionId.isBlank()) {
                return ReqResult.error("0001", "Invalid parameters for updating user sessionId");
            }
            boolean updated = customPageConversationRepository.updateUserSessionIdByRequestId(projectId, requestId,
                    sessionId, userContext.getUserId());
            return updated ? ReqResult.success() : ReqResult.error("0001", "No matched USER conversation found");
        } catch (Exception e) {
            log.error("[Domain] update user sessionId by requestId failed, project Id={}, requestId={}", projectId, requestId, e);
            return ReqResult.error("0001", "Failed to update user sessionId: " + e.getMessage());
        }
    }

    @Override
    public ReqResult<Void> deleteByProjectId(Long projectId, UserContext userContext) {
        try {
            if (projectId == null || projectId <= 0) {
                return ReqResult.error("0001", "projectId is required or invalid");
            }
            boolean deleted = customPageConversationRepository.deleteByProjectId(projectId, userContext.getUserId());
            return deleted ? ReqResult.success() : ReqResult.error("0001", "Delete conversation records failed");
        } catch (Exception e) {
            log.error("[Domain] delete conversations by project failed, project Id={}", projectId, e);
            return ReqResult.error("0001", "Failed to delete conversations: " + e.getMessage());
        }
    }

}
