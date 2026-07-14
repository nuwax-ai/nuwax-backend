package com.xspaceagi.custompage.infra.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xspaceagi.custompage.domain.model.CustomPageConversationModel;
import com.xspaceagi.custompage.domain.repository.ICustomPageConversationRepository;
import com.xspaceagi.custompage.infra.dao.entity.CustomPageConversation;
import com.xspaceagi.custompage.infra.dao.service.ICustomPageConversationService;
import com.xspaceagi.custompage.infra.translator.ICustomPageConversationTranslator;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.enums.YnEnum;
import com.xspaceagi.system.spec.page.SuperPage;

import jakarta.annotation.Resource;

@Repository
public class CustomPageConversationRepositoryImpl implements ICustomPageConversationRepository {

    @Resource
    private ICustomPageConversationService customPageConversationService;

    @Resource
    private ICustomPageConversationTranslator customPageConversationTranslator;

    @Override
    public Long save(CustomPageConversationModel model, UserContext userContext) {
        if (model == null) {
            throw new IllegalArgumentException("model is required");
        }
        if (userContext == null) {
            throw new IllegalArgumentException("userContext is required");
        }

        CustomPageConversation entity = customPageConversationTranslator.convertToEntity(model);

        entity.setCreatorId(userContext.getUserId());
        entity.setCreatorName(userContext.getUserName());
        entity.setTenantId(userContext.getTenantId());
        entity.setSpaceId(model.getSpaceId());
        entity.setYn(YnEnum.Y.getKey());

        customPageConversationService.save(entity);
        return entity.getId();
    }

    @Override
    public List<CustomPageConversationModel> listByProjectId(Long projectId, Long userId) {
        var wrapper = Wrappers.<CustomPageConversation>lambdaQuery()
                .eq(CustomPageConversation::getProjectId, projectId)
                // 不过滤用户
                // .eq(CustomPageConversation::getCreatorId, userId)
                .eq(CustomPageConversation::getYn, YnEnum.Y.getKey())
                .orderByAsc(CustomPageConversation::getCreated);

        List<CustomPageConversation> entities = customPageConversationService.list(wrapper);
        if (entities == null || entities.isEmpty()) {
            return null;
        }

        return entities.stream()
                .map(customPageConversationTranslator::convertToModel)
                .collect(Collectors.toList());
    }

    @Override
    public SuperPage<CustomPageConversationModel> pageQuery(CustomPageConversationModel queryModel, Long current,
            Long pageSize) {
        if (queryModel == null) {
            throw new IllegalArgumentException("queryModel is required");
        }
        if (current == null || current <= 0) {
            throw new IllegalArgumentException("current is required or invalid");
        }
        if (pageSize == null || pageSize <= 0) {
            throw new IllegalArgumentException("pageSize is required or invalid");
        }

        var wrapper = Wrappers.<CustomPageConversation>lambdaQuery()
                .eq(queryModel.getProjectId() != null, CustomPageConversation::getProjectId, queryModel.getProjectId())
                // .eq(queryModel.getCreatorId() != null, CustomPageConversation::getCreatorId,
                // queryModel.getCreatorId())
                .eq(CustomPageConversation::getYn, YnEnum.Y.getKey())
                .orderByDesc(CustomPageConversation::getCreated); // 倒序查询

        Page<CustomPageConversation> page = new Page<>(current, pageSize);
        Page<CustomPageConversation> resultPage = customPageConversationService.page(page, wrapper);

        List<CustomPageConversationModel> modelList = new ArrayList<>();
        if (resultPage.getRecords() != null && !resultPage.getRecords().isEmpty()) {
            modelList = resultPage.getRecords().stream()
                    .map(customPageConversationTranslator::convertToModel)
                    .collect(Collectors.toList());
        }

        SuperPage<CustomPageConversationModel> superPage = new SuperPage<>(current, pageSize, resultPage.getTotal());
        superPage.setRecords(modelList);

        return superPage;
    }

    @Override
    public CustomPageConversationModel findLatestUserBySessionId(Long projectId, String sessionId) {
        if (projectId == null || sessionId == null || sessionId.isBlank()) {
            return null;
        }
        var wrapper = Wrappers.<CustomPageConversation>lambdaQuery()
                .eq(CustomPageConversation::getProjectId, projectId)
                .eq(CustomPageConversation::getSessionId, sessionId)
                .eq(CustomPageConversation::getRole, "USER")
                .eq(CustomPageConversation::getYn, YnEnum.Y.getKey())
                .orderByDesc(CustomPageConversation::getCreated)
                .last("LIMIT 1");
        CustomPageConversation entity = customPageConversationService.getOne(wrapper, false);
        if (entity == null) {
            return null;
        }
        return customPageConversationTranslator.convertToModel(entity);
    }

    @Override
    public CustomPageConversationModel findLatestUserByProjectId(Long projectId) {
        if (projectId == null) {
            return null;
        }
        var wrapper = Wrappers.<CustomPageConversation>lambdaQuery()
                .eq(CustomPageConversation::getProjectId, projectId)
                .eq(CustomPageConversation::getRole, "USER")
                .eq(CustomPageConversation::getYn, YnEnum.Y.getKey())
                .orderByDesc(CustomPageConversation::getCreated)
                .last("LIMIT 1");
        CustomPageConversation entity = customPageConversationService.getOne(wrapper, false);
        if (entity == null) {
            return null;
        }
        return customPageConversationTranslator.convertToModel(entity);
    }

    @Override
    public CustomPageConversationModel findAssistantByProjectIdAndRequestId(Long projectId, String requestId) {
        if (projectId == null || requestId == null || requestId.isBlank()) {
            return null;
        }
        var wrapper = Wrappers.<CustomPageConversation>lambdaQuery()
                .eq(CustomPageConversation::getProjectId, projectId)
                .eq(CustomPageConversation::getRequestId, requestId)
                .eq(CustomPageConversation::getRole, "ASSISTANT")
                .eq(CustomPageConversation::getYn, YnEnum.Y.getKey())
                .orderByDesc(CustomPageConversation::getCreated)
                .last("LIMIT 1");
        CustomPageConversation entity = customPageConversationService.getOne(wrapper, false);
        if (entity == null) {
            return null;
        }
        return customPageConversationTranslator.convertToModel(entity);
    }

    @Override
    public CustomPageConversationModel findUserByProjectIdAndRequestId(Long projectId, String requestId) {
        if (projectId == null || requestId == null || requestId.isBlank()) {
            return null;
        }
        var wrapper = Wrappers.<CustomPageConversation>lambdaQuery()
                .eq(CustomPageConversation::getProjectId, projectId)
                .eq(CustomPageConversation::getRequestId, requestId)
                .eq(CustomPageConversation::getRole, "USER")
                .eq(CustomPageConversation::getYn, YnEnum.Y.getKey())
                .orderByDesc(CustomPageConversation::getCreated)
                .last("LIMIT 1");
        CustomPageConversation entity = customPageConversationService.getOne(wrapper, false);
        if (entity == null) {
            return null;
        }
        return customPageConversationTranslator.convertToModel(entity);
    }

    @Override
    public CustomPageConversationModel findById(Long id) {
        if (id == null) {
            return null;
        }
        CustomPageConversation entity = customPageConversationService.getById(id);
        if (entity == null || !YnEnum.Y.getKey().equals(entity.getYn())) {
            return null;
        }
        return customPageConversationTranslator.convertToModel(entity);
    }

    @Override
    public boolean updateAssistantContent(Long id, String content, String requestId, UserContext userContext) {
        if (id == null || content == null || content.isBlank()) {
            return false;
        }
        CustomPageConversation updateEntity = new CustomPageConversation();
        updateEntity.setId(id);
        updateEntity.setContent(content);
        if (requestId != null && !requestId.isBlank()) {
            updateEntity.setRequestId(requestId);
        }
        if (userContext != null) {
            updateEntity.setModifiedId(userContext.getUserId());
            updateEntity.setModifiedName(userContext.getUserName());
        }
        return customPageConversationService.updateById(updateEntity);
    }

    @Override
    public boolean updateUserSessionIdByRequestId(Long projectId, String requestId, String sessionId, Long userId) {
        if (projectId == null || requestId == null || requestId.isBlank() || sessionId == null || sessionId.isBlank()
                || userId == null) {
            return false;
        }
        var wrapper = Wrappers.<CustomPageConversation>lambdaUpdate()
                .eq(CustomPageConversation::getProjectId, projectId)
                .eq(CustomPageConversation::getRequestId, requestId)
                .eq(CustomPageConversation::getRole, "USER")
                .eq(CustomPageConversation::getCreatorId, userId)
                .eq(CustomPageConversation::getYn, YnEnum.Y.getKey());
        CustomPageConversation updateEntity = new CustomPageConversation();
        updateEntity.setSessionId(sessionId);
        return customPageConversationService.update(updateEntity, wrapper);
    }

    @Override
    public boolean deleteByProjectId(Long projectId, Long userId) {
        if (projectId == null || projectId <= 0) {
            return false;
        }
        var wrapper = Wrappers.<CustomPageConversation>lambdaQuery()
                .eq(CustomPageConversation::getProjectId, projectId);
        return customPageConversationService.remove(wrapper);
    }

}
