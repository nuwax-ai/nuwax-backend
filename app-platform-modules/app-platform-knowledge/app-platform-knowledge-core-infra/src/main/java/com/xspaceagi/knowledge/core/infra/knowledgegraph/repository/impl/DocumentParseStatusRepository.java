package com.xspaceagi.knowledge.core.infra.knowledgegraph.repository.impl;

import com.xspaceagi.knowledge.core.infra.knowledgegraph.repository.entity.DocumentParseStatus;
import com.xspaceagi.knowledge.core.infra.dao.service.DocumentParseStatusService;
import com.xspaceagi.knowledge.domain.model.DocumentParseStatusModel;
import com.xspaceagi.knowledge.domain.repository.IDocumentParseStatusRepository;
import com.xspaceagi.knowledge.core.infra.translator.IDocumentParseStatusTranslator;
import com.xspaceagi.system.spec.common.UserContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 文档解析状态仓储实现
 *
 * @author system
 * @date 2026-04-16
 */
@Slf4j
@Repository
public class DocumentParseStatusRepository implements IDocumentParseStatusRepository {

    @Resource
    private IDocumentParseStatusTranslator documentParseStatusTranslator;

    @Resource
    private DocumentParseStatusService documentParseStatusService;

    @Override
    public DocumentParseStatusModel queryByDocumentId(Long documentId) {
        DocumentParseStatus entity = documentParseStatusService.queryByDocumentId(documentId);
        return documentParseStatusTranslator.convertToModel(entity);
    }

    @Override
    public List<DocumentParseStatusModel> queryListByKbId(Long kbId) {
        List<DocumentParseStatus> list = documentParseStatusService.queryListByKbId(kbId);
        return list.stream()
                .map(documentParseStatusTranslator::convertToModel)
                .collect(Collectors.toList());
    }

    @Override
    public List<DocumentParseStatusModel> queryListByDocumentIds(List<Long> documentIds) {
        List<DocumentParseStatus> list = documentParseStatusService.queryListByDocumentIds(documentIds);
        return list.stream()
                .map(documentParseStatusTranslator::convertToModel)
                .collect(Collectors.toList());
    }

    @Override
    public Long addInfo(DocumentParseStatusModel model, UserContext userContext) {
        model.setCreated(LocalDateTime.now());
        model.setModified(LocalDateTime.now());
        if (model.getTenantId() == null && userContext != null) {
            model.setTenantId(userContext.getTenantId());
        }
        DocumentParseStatus entity = documentParseStatusTranslator.convertToEntity(model);
        return documentParseStatusService.addInfo(entity);
    }

    @Override
    public Long updateInfo(DocumentParseStatusModel model, UserContext userContext) {
        model.setModified(LocalDateTime.now());
        if (model.getTenantId() == null && userContext != null) {
            model.setTenantId(userContext.getTenantId());
        }
        DocumentParseStatus entity = documentParseStatusTranslator.convertToEntity(model);
        return documentParseStatusService.updateInfo(entity);
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        documentParseStatusService.deleteByDocumentId(documentId);
    }

    @Override
    public List<Long> findKbIdsWithTripleStatus(List<Long> kbIds, Integer tripleStatus) {
        return documentParseStatusService.findKbIdsWithTripleStatus(kbIds, tripleStatus);
    }

    @Override
    public List<DocumentParseStatusModel> queryListByTripleStatus(Integer tripleStatus) {
        List<DocumentParseStatus> list = documentParseStatusService.queryListByTripleStatus(tripleStatus);
        return list.stream()
                .map(documentParseStatusTranslator::convertToModel)
                .collect(Collectors.toList());
    }
}
