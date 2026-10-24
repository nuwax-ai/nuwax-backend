package com.xspaceagi.knowledge.core.infra.translator.impl;

import com.xspaceagi.knowledge.core.infra.knowledgegraph.repository.entity.DocumentParseStatus;
import com.xspaceagi.knowledge.core.infra.translator.IDocumentParseStatusTranslator;
import com.xspaceagi.knowledge.domain.model.DocumentParseStatusModel;
import org.springframework.stereotype.Service;

/**
 * 文档三元组解析状态转换器实现
 *
 * @author system
 * @date 2026-04-16
 */
@Service
public class DocumentParseStatusTranslatorImpl implements IDocumentParseStatusTranslator {

    @Override
    public DocumentParseStatusModel convertToModel(DocumentParseStatus entity) {
        if (entity == null) {
            return null;
        }
        DocumentParseStatusModel model = new DocumentParseStatusModel();
        model.setId(entity.getId());
        model.setDocumentId(entity.getDocumentId());
        model.setKbId(entity.getKbId());
        model.setTripleStatus(entity.getTripleStatus());
        model.setTaskId(entity.getTaskId());
        model.setTenantId(entity.getTenantId());
        model.setCreated(entity.getCreated());
        model.setModified(entity.getModified());
        return model;
    }

    @Override
    public DocumentParseStatus convertToEntity(DocumentParseStatusModel model) {
        if (model == null) {
            return null;
        }
        DocumentParseStatus entity = new DocumentParseStatus();
        entity.setId(model.getId());
        entity.setDocumentId(model.getDocumentId());
        entity.setKbId(model.getKbId());
        entity.setTripleStatus(model.getTripleStatus());
        entity.setTaskId(model.getTaskId());
        entity.setTenantId(model.getTenantId());
        entity.setCreated(model.getCreated());
        entity.setModified(model.getModified());
        return entity;
    }
}
