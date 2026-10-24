package com.xspaceagi.knowledge.core.infra.translator.impl;

import com.xspaceagi.knowledge.core.infra.knowledgegraph.repository.entity.DocumentParseStatusRecord;
import com.xspaceagi.knowledge.core.infra.translator.IDocumentParseStatusRecordTranslator;
import com.xspaceagi.knowledge.domain.model.DocumentParseStatusRecordModel;
import org.springframework.stereotype.Service;

/**
 * 文档解析状态记录转换器实现
 *
 * @author system
 * @date 2026-05-08
 */
@Service
public class DocumentParseStatusRecordTranslatorImpl implements IDocumentParseStatusRecordTranslator {

    @Override
    public DocumentParseStatusRecordModel convertToModel(DocumentParseStatusRecord entity) {
        if (entity == null) {
            return null;
        }
        DocumentParseStatusRecordModel model = new DocumentParseStatusRecordModel();
        model.setId(entity.getId());
        model.setCreateTime(entity.getCreateTime());
        model.setExecuteState(entity.getExecuteState());
        model.setSuccessCount(entity.getSuccessCount());
        model.setErrorCount(entity.getErrorCount());
        model.setStartTime(entity.getStartTime());
        model.setEndTime(entity.getEndTime());
        model.setExecuteContent(entity.getExecuteContent());
        model.setExecuteErrorLogs(entity.getExecuteErrorLogs());
        return model;
    }

    @Override
    public DocumentParseStatusRecord convertToEntity(DocumentParseStatusRecordModel model) {
        if (model == null) {
            return null;
        }
        DocumentParseStatusRecord entity = new DocumentParseStatusRecord();
        entity.setId(model.getId());
        entity.setCreateTime(model.getCreateTime());
        entity.setExecuteState(model.getExecuteState());
        entity.setSuccessCount(model.getSuccessCount());
        entity.setErrorCount(model.getErrorCount());
        entity.setStartTime(model.getStartTime());
        entity.setEndTime(model.getEndTime());
        entity.setExecuteContent(model.getExecuteContent());
        entity.setExecuteErrorLogs(model.getExecuteErrorLogs());
        return entity;
    }
}
