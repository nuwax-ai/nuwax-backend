package com.xspaceagi.knowledge.core.infra.knowledgegraph.repository.impl;

import com.xspaceagi.knowledge.core.infra.dao.service.DocumentParseStatusRecordService;
import com.xspaceagi.knowledge.core.infra.knowledgegraph.repository.entity.DocumentParseStatusRecord;
import com.xspaceagi.knowledge.core.infra.translator.IDocumentParseStatusRecordTranslator;
import com.xspaceagi.knowledge.domain.model.DocumentParseStatusRecordModel;
import com.xspaceagi.knowledge.domain.repository.IDocumentParseStatusRecordRepository;
import com.xspaceagi.system.spec.common.UserContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

/**
 * 文档解析状态记录仓储实现
 *
 * @author system
 * @date 2026-05-08
 */
@Slf4j
@Repository
public class DocumentParseStatusRecordRepository implements IDocumentParseStatusRecordRepository {

    @Resource
    private IDocumentParseStatusRecordTranslator documentParseStatusRecordTranslator;

    @Resource
    private DocumentParseStatusRecordService documentParseStatusRecordService;

    @Override
    public Long addInfo(DocumentParseStatusRecordModel model, UserContext userContext) {
        DocumentParseStatusRecord entity = documentParseStatusRecordTranslator.convertToEntity(model);
        return documentParseStatusRecordService.addInfo(entity);
    }

    @Override
    public Long updateInfo(DocumentParseStatusRecordModel model, UserContext userContext) {
        DocumentParseStatusRecord entity = documentParseStatusRecordTranslator.convertToEntity(model);
        return documentParseStatusRecordService.updateInfo(entity);
    }
}
