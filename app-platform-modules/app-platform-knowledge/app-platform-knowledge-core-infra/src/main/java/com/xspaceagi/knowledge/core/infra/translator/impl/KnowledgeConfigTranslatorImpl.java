package com.xspaceagi.knowledge.core.infra.translator.impl;

import com.xspaceagi.knowledge.core.infra.dao.entity.KnowledgeConfig;
import com.xspaceagi.knowledge.core.infra.translator.IKnowledgeConfigTranslator;
import com.xspaceagi.knowledge.domain.model.KnowledgeConfigModel;
import com.xspaceagi.system.spec.enums.YnEnum;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class KnowledgeConfigTranslatorImpl
        implements IKnowledgeConfigTranslator {

    @Override
    public KnowledgeConfigModel convertToModel(KnowledgeConfig entity) {
        if (Objects.isNull(entity)) {
            return null;
        }
        KnowledgeConfigModel knowledgeConfigModel = new KnowledgeConfigModel();
        knowledgeConfigModel.setId(entity.getId());
        knowledgeConfigModel.setName(entity.getName());
        knowledgeConfigModel.setDescription(entity.getDescription());
        knowledgeConfigModel.setPubStatus(entity.getPubStatus());
        knowledgeConfigModel.setEmbeddingModelId(entity.getEmbeddingModelId());
        knowledgeConfigModel.setChatModelId(entity.getChatModelId());
        knowledgeConfigModel.setDataType(entity.getDataType());
        knowledgeConfigModel.setSpaceId(entity.getSpaceId());
        knowledgeConfigModel.setTenantId(entity.getTenantId());
        knowledgeConfigModel.setIcon(entity.getIcon());
        knowledgeConfigModel.setFileSize(entity.getFileSize());
        knowledgeConfigModel.setCreated(entity.getCreated());
        knowledgeConfigModel.setCreatorId(entity.getCreatorId());
        knowledgeConfigModel.setCreatorName(entity.getCreatorName());
        knowledgeConfigModel.setModified(entity.getModified());
        knowledgeConfigModel.setModifiedId(entity.getModifiedId());
        knowledgeConfigModel.setModifiedName(entity.getModifiedName());
        knowledgeConfigModel.setWorkflowId(entity.getWorkflowId());
        knowledgeConfigModel.setAccessControl(entity.getAccessControl());
        knowledgeConfigModel.setFulltextSyncStatus(entity.getFulltextSyncStatus());
        knowledgeConfigModel.setFulltextSyncTime(entity.getFulltextSyncTime());
        knowledgeConfigModel.setFulltextSegmentCount(entity.getFulltextSegmentCount());
        return knowledgeConfigModel;
    }

    @Override
    public KnowledgeConfig convertToEntity(KnowledgeConfigModel model) {
        if (Objects.isNull(model)) {
            return null;
        }
        KnowledgeConfig knowledgeConfig = new KnowledgeConfig();
        knowledgeConfig.setId(model.getId());
        knowledgeConfig.setName(model.getName());
        knowledgeConfig.setDescription(model.getDescription());
        knowledgeConfig.setPubStatus(model.getPubStatus());
        knowledgeConfig.setEmbeddingModelId(model.getEmbeddingModelId());
        knowledgeConfig.setChatModelId(model.getChatModelId());
        knowledgeConfig.setDataType(model.getDataType());
        knowledgeConfig.setSpaceId(model.getSpaceId());
        knowledgeConfig.setTenantId(model.getTenantId());
        knowledgeConfig.setIcon(model.getIcon());
        knowledgeConfig.setFileSize(model.getFileSize());
        knowledgeConfig.setCreated(model.getCreated());
        knowledgeConfig.setCreatorId(model.getCreatorId());
        knowledgeConfig.setCreatorName(model.getCreatorName());
        knowledgeConfig.setModified(model.getModified());
        knowledgeConfig.setModifiedId(model.getModifiedId());
        knowledgeConfig.setModifiedName(model.getModifiedName());
        knowledgeConfig.setWorkflowId(model.getWorkflowId());
        knowledgeConfig.setAccessControl(model.getAccessControl());
        knowledgeConfig.setFulltextSyncStatus(model.getFulltextSyncStatus());
        knowledgeConfig.setFulltextSyncTime(model.getFulltextSyncTime());
        knowledgeConfig.setFulltextSegmentCount(model.getFulltextSegmentCount());
        knowledgeConfig.setYn(YnEnum.Y.getKey());
        return knowledgeConfig;
    }
}
