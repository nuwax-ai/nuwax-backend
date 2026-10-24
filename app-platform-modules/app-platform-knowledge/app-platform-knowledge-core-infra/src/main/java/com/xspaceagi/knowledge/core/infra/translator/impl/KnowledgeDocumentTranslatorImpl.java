package com.xspaceagi.knowledge.core.infra.translator.impl;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.knowledge.core.infra.dao.entity.KnowledgeDocument;
import com.xspaceagi.knowledge.core.infra.translator.IKnowledgeDocumentTranslator;
import com.xspaceagi.knowledge.sdk.vo.SegmentConfigModel;
import com.xspaceagi.knowledge.domain.model.KnowledgeDocumentModel;
import com.xspaceagi.system.spec.enums.YnEnum;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class KnowledgeDocumentTranslatorImpl implements IKnowledgeDocumentTranslator {
    @Override
    public KnowledgeDocumentModel convertToModel(KnowledgeDocument entity) {
        if (entity == null) {
            return null;
        }
        KnowledgeDocumentModel knowledgeDocumentModel = new KnowledgeDocumentModel();
        knowledgeDocumentModel.setId(entity.getId());
        knowledgeDocumentModel.setKbId(entity.getKbId());
        knowledgeDocumentModel.setName(entity.getName());
        knowledgeDocumentModel.setDocUrl(entity.getDocUrl());
        knowledgeDocumentModel.setPubStatus(entity.getPubStatus());
        knowledgeDocumentModel.setDocStatus(entity.getDocStatus());
        knowledgeDocumentModel.setHasQa(entity.getHasQa());
        knowledgeDocumentModel.setHasEmbedding(entity.getHasEmbedding());
        knowledgeDocumentModel.setDataType(entity.getDataType());
        knowledgeDocumentModel.setFileContent(entity.getFileContent());
        knowledgeDocumentModel.setFileSize(entity.getFileSize());
        var segmentConfigModel = JSON.parseObject(entity.getSegment(), SegmentConfigModel.class);

        knowledgeDocumentModel.setSegmentConfig(segmentConfigModel);
        knowledgeDocumentModel.setSpaceId(entity.getSpaceId());
        knowledgeDocumentModel.setCreated(entity.getCreated());
        knowledgeDocumentModel.setCreatorId(entity.getCreatorId());
        knowledgeDocumentModel.setCreatorName(entity.getCreatorName());
        knowledgeDocumentModel.setModified(entity.getModified());
        knowledgeDocumentModel.setModifiedId(entity.getModifiedId());
        knowledgeDocumentModel.setModifiedName(entity.getModifiedName());
        return knowledgeDocumentModel;

    }

    @Override
    public KnowledgeDocument convertToEntity(KnowledgeDocumentModel model) {
        if (model == null) {
            return null;
        }
        KnowledgeDocument knowledgeDocument = new KnowledgeDocument();
        knowledgeDocument.setId(model.getId());
        knowledgeDocument.setKbId(model.getKbId());
        knowledgeDocument.setName(model.getName());
        knowledgeDocument.setDocUrl(model.getDocUrl());
        knowledgeDocument.setPubStatus(model.getPubStatus());
        knowledgeDocument.setDocStatus(model.getDocStatus());
        knowledgeDocument.setHasQa(model.getHasQa());
        knowledgeDocument.setHasEmbedding(model.getHasEmbedding());
        knowledgeDocument.setDataType(model.getDataType());
        knowledgeDocument.setFileContent(model.getFileContent());
        knowledgeDocument.setFileSize(model.getFileSize());
        //判断 分段配置是否为空,如果不判断,直接转换为json,会覆盖掉原有json内容
        if (Objects.nonNull(model.getSegmentConfig())) {
            var segmentJson = JSON.toJSONString(model.getSegmentConfig());
            knowledgeDocument.setSegment(segmentJson);
        }

        knowledgeDocument.setSpaceId(model.getSpaceId());
        knowledgeDocument.setCreated(model.getCreated());
        knowledgeDocument.setCreatorId(model.getCreatorId());
        knowledgeDocument.setCreatorName(model.getCreatorName());
        knowledgeDocument.setModified(model.getModified());
        knowledgeDocument.setModifiedId(model.getModifiedId());
        knowledgeDocument.setModifiedName(model.getModifiedName());
        knowledgeDocument.setYn(YnEnum.Y.getKey());
        return knowledgeDocument;

    }
}
