package com.xspaceagi.knowledge.core.infra.dao.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.knowledge.core.infra.knowledgegraph.repository.entity.DocumentParseStatus;
import com.xspaceagi.knowledge.core.infra.dao.mapper.DocumentParseStatusMapper;
import com.xspaceagi.knowledge.core.infra.dao.service.DocumentParseStatusService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 文档解析状态 Service 实现
 *
 * @author system
 * @date 2026-04-16
 */
@Service
public class DocumentParseStatusServiceImpl extends ServiceImpl<DocumentParseStatusMapper, DocumentParseStatus>
        implements DocumentParseStatusService {

    @Resource
    private DocumentParseStatusMapper documentParseStatusMapper;

    @Override
    public DocumentParseStatus queryByDocumentId(Long documentId) {
        LambdaQueryWrapper<DocumentParseStatus> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(DocumentParseStatus::getDocumentId, documentId)
                .last("limit 1");
        return this.getOne(queryWrapper);
    }

    @Override
    public List<DocumentParseStatus> queryListByKbId(Long kbId) {
        LambdaQueryWrapper<DocumentParseStatus> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(DocumentParseStatus::getKbId, kbId);
        return this.list(queryWrapper);
    }

    @Override
    public List<DocumentParseStatus> queryListByDocumentIds(List<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<DocumentParseStatus> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(DocumentParseStatus::getDocumentId, documentIds);
        return this.list(queryWrapper);
    }

    @Override
    public Long addInfo(DocumentParseStatus entity) {
        this.save(entity);
        return entity.getId();
    }

    @Override
    public Long updateInfo(DocumentParseStatus entity) {
        this.updateById(entity);
        return entity.getId();
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        LambdaQueryWrapper<DocumentParseStatus> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(DocumentParseStatus::getDocumentId, documentId);
        this.remove(queryWrapper);
    }

    @Override
    public List<Long> findKbIdsWithTripleStatus(List<Long> kbIds, Integer tripleStatus) {
        if (kbIds == null || kbIds.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<DocumentParseStatus> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.select(DocumentParseStatus::getKbId)
                .in(DocumentParseStatus::getKbId, kbIds)
                .eq(DocumentParseStatus::getTripleStatus, tripleStatus)
                .last("limit 100");

        return documentParseStatusMapper.selectObjs(queryWrapper).stream()
                .map(obj -> (Long) obj)
                .distinct()
                .toList();
    }

    @Override
    public List<DocumentParseStatus> queryListByTripleStatus(Integer tripleStatus) {
        LambdaQueryWrapper<DocumentParseStatus> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(DocumentParseStatus::getTripleStatus, tripleStatus);
        return this.list(queryWrapper);
    }
}
