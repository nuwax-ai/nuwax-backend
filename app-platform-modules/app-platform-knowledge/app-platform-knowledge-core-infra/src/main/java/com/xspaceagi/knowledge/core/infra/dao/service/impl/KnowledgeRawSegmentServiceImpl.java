package com.xspaceagi.knowledge.core.infra.dao.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.knowledge.core.infra.dao.entity.KnowledgeRawSegment;
import com.xspaceagi.knowledge.core.infra.dao.mapper.KnowledgeRawSegmentMapper;
import com.xspaceagi.knowledge.core.infra.dao.service.KnowledgeRawSegmentService;
import com.xspaceagi.knowledge.core.spec.enums.QaStatusEnum;
import com.xspaceagi.system.spec.enums.YnEnum;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.KnowledgeException;

import jakarta.annotation.Resource;

/**
 * @author soddy
 * @description 针对表【knowledge_raw_segment(原始分段（也称chunk）表，这些信息待生成问答后可以不再保存)】的数据库操作Service实现
 * @createDate 2025-01-21 15:33:38
 */
@Service
public class KnowledgeRawSegmentServiceImpl extends ServiceImpl<KnowledgeRawSegmentMapper, KnowledgeRawSegment>
        implements KnowledgeRawSegmentService {

    @Lazy
    @Resource
    private KnowledgeRawSegmentService self;

    @Override
    public List<KnowledgeRawSegment> queryListByIds(List<Long> ids) {
        LambdaQueryWrapper<KnowledgeRawSegment> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KnowledgeRawSegment::getYn, YnEnum.Y.getKey())
                .in(KnowledgeRawSegment::getId, ids);
        return this.list(queryWrapper);
    }

    @Override
    public KnowledgeRawSegment queryOneInfoById(Long id) {
        LambdaQueryWrapper<KnowledgeRawSegment> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KnowledgeRawSegment::getYn, YnEnum.Y.getKey())
                .eq(KnowledgeRawSegment::getId, id);
        return this.getOne(queryWrapper);
    }

    @Override
    public List<KnowledgeRawSegment> queryListByDocId(Long docId) {
        LambdaQueryWrapper<KnowledgeRawSegment> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KnowledgeRawSegment::getYn, YnEnum.Y.getKey())
                .eq(KnowledgeRawSegment::getDocId, docId);
        return this.list(queryWrapper);
    }

    @Override
    public Long updateInfo(KnowledgeRawSegment entity) {
        var updateObj = this.getById(entity.getId());

        if (Objects.isNull(updateObj)) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }
        entity.setCreated(null);
        entity.setModified(null);

        this.updateById(entity);

        return entity.getId();
    }

    @Override
    public Long addInfo(KnowledgeRawSegment entity) {
        entity.setId(null);
        entity.setCreated(null);
        entity.setModified(null);

        this.save(entity);

        return entity.getId();
    }

    @Override
    public void deleteById(Long id) {
        this.removeById(id);
    }

    @Override
    public void deleteByConfigId(Long kbId) {
        LambdaQueryWrapper<KnowledgeRawSegment> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KnowledgeRawSegment::getKbId, kbId);

        this.remove(queryWrapper);
    }

    @Override
    public void deleteByConfigDocumentId(Long docId) {
        LambdaQueryWrapper<KnowledgeRawSegment> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KnowledgeRawSegment::getDocId, docId);

        this.remove(queryWrapper);
    }

    @Override
    public void batchUpdateQaStatus(List<KnowledgeRawSegment> segments) {

        var updateObjList = segments.stream().map(segment -> {
            var updateObj = new KnowledgeRawSegment();
            updateObj.setId(segment.getId());
            updateObj.setQaStatus(segment.getQaStatus());
            updateObj.setModified(LocalDateTime.now());
            updateObj.setModifiedId(segment.getModifiedId());
            updateObj.setModifiedName(segment.getModifiedName());
            return updateObj;
        }).toList();

        this.self.updateBatchById(updateObjList);
    }

    @Override
    public Long queryCountForPendingQaByDocId(Long docId) {
        LambdaQueryWrapper<KnowledgeRawSegment> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KnowledgeRawSegment::getYn, YnEnum.Y.getKey())
                .eq(KnowledgeRawSegment::getDocId, docId)
                .eq(KnowledgeRawSegment::getQaStatus, QaStatusEnum.PENDING.getCode());
        return this.count(queryWrapper);
    }

    @Override
    public List<KnowledgeRawSegment> queryListForPendingQaByDocId(Long docId) {
        LambdaQueryWrapper<KnowledgeRawSegment> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KnowledgeRawSegment::getYn, YnEnum.Y.getKey())
                .eq(KnowledgeRawSegment::getDocId, docId)
                .eq(KnowledgeRawSegment::getQaStatus, QaStatusEnum.PENDING.getCode());
        return this.list(queryWrapper);
    }

    @Override
    public List<KnowledgeRawSegment> queryListByDocIds(List<Long> docIds) {
        LambdaQueryWrapper<KnowledgeRawSegment> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KnowledgeRawSegment::getYn, YnEnum.Y.getKey())
                .in(KnowledgeRawSegment::getDocId, docIds);
        return this.list(queryWrapper);
    }
}
