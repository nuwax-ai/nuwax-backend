package com.xspaceagi.knowledge.core.infra.translator;

import com.xspaceagi.knowledge.core.infra.knowledgegraph.repository.entity.DocumentParseStatus;
import com.xspaceagi.knowledge.domain.model.DocumentParseStatusModel;

/**
 * 文档解析状态转换器
 *
 * @author system
 * @date 2026-04-16
 */
public interface IDocumentParseStatusTranslator {

    /**
     * Entity 转 Model
     */
    DocumentParseStatusModel convertToModel(DocumentParseStatus entity);

    /**
     * Model 转 Entity
     */
    DocumentParseStatus convertToEntity(DocumentParseStatusModel model);
}