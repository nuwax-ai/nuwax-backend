package com.xspaceagi.knowledge.core.infra.translator;

import com.xspaceagi.knowledge.core.infra.knowledgegraph.repository.entity.DocumentParseStatusRecord;
import com.xspaceagi.knowledge.domain.model.DocumentParseStatusRecordModel;

/**
 * 文档解析状态记录转换器
 *
 * @author system
 * @date 2026-05-08
 */
public interface IDocumentParseStatusRecordTranslator {

    /**
     * Entity 转 Model
     */
    DocumentParseStatusRecordModel convertToModel(DocumentParseStatusRecord entity);

    /**
     * Model 转 Entity
     */
    DocumentParseStatusRecord convertToEntity(DocumentParseStatusRecordModel model);
}
