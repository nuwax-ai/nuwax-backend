package com.xspaceagi.knowledge.core.infra.dao.service.impl;

import java.util.List;
import java.util.Objects;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.knowledge.core.infra.dao.entity.KnowledgeQaSegment;
import com.xspaceagi.knowledge.core.infra.dao.mapper.KnowledgeQaSegmentMapper;
import com.xspaceagi.knowledge.core.infra.dao.service.KnowledgeQaSegmentService;
import com.xspaceagi.system.spec.enums.YnEnum;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.KnowledgeException;

import jakarta.annotation.Resource;

/**
 * @author soddy
 * @description 针对表【knowledge_qa_segment(问答表)】的数据库操作Service实现
 * @createDate 2025-01-21 15:33:38
 */
@Service
public class KnowledgeQaSegmentServiceImpl extends ServiceImpl<KnowledgeQaSegmentMapper, KnowledgeQaSegment>
        implements KnowledgeQaSegmentService {

    @Lazy
    @Resource
    private KnowledgeQaSegmentService self;

    @Override
    public List<KnowledgeQaSegment> queryListByIds(List<Long> ids) {
        LambdaQueryWrapper<KnowledgeQaSegment> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KnowledgeQaSegment::getYn, YnEnum.Y.getKey())
                .in(KnowledgeQaSegment::getId, ids);
        return this.list(queryWrapper);
    }

    @Override
    public List<KnowledgeQaSegment> queryListByDocIdAndNoEmbedding(Long docId) {
        LambdaQueryWrapper<KnowledgeQaSegment> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KnowledgeQaSegment::getYn, YnEnum.Y.getKey())
                .eq(KnowledgeQaSegment::getDocId, docId)
                .eq(KnowledgeQaSegment::getHasEmbedding, false);
        return this.list(queryWrapper);
    }

    @Override
    public Long queryCountByDocIdAndNoEmbedding(Long docId) {
        LambdaQueryWrapper<KnowledgeQaSegment> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KnowledgeQaSegment::getYn, YnEnum.Y.getKey())
                .eq(KnowledgeQaSegment::getDocId, docId)
                .eq(KnowledgeQaSegment::getHasEmbedding, false);
        return this.count(queryWrapper);
    }

    @Override
    public KnowledgeQaSegment queryOneInfoById(Long id) {
        LambdaQueryWrapper<KnowledgeQaSegment> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KnowledgeQaSegment::getYn, YnEnum.Y.getKey())
                .eq(KnowledgeQaSegment::getId, id);
        return this.getOne(queryWrapper);
    }

    @Override
    public Long updateInfo(KnowledgeQaSegment entity) {
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
    public Long addInfo(KnowledgeQaSegment entity) {
        entity.setId(null);
        entity.setCreated(null);
        entity.setModified(null);

        this.save(entity);

        return entity.getId();
    }

    @Override
    public void batchAddInfo(List<KnowledgeQaSegment> list) {
        for (KnowledgeQaSegment entity : list) {
            entity.setId(null);
            entity.setCreated(null);
            entity.setModified(null);
        }
        this.self.saveBatch(list);
    }

    @Override
    public void deleteById(Long id) {
        this.removeById(id);
    }

    @Override
    public void deleteByConfigId(Long kbId) {
        LambdaQueryWrapper<KnowledgeQaSegment> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KnowledgeQaSegment::getKbId, kbId);

        this.remove(queryWrapper);
    }

    @Override
    public void deleteByConfigDocumentId(Long docId) {
        LambdaQueryWrapper<KnowledgeQaSegment> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KnowledgeQaSegment::getDocId, docId);

        this.remove(queryWrapper);
    }

    @Override
    public List<KnowledgeQaSegment> queryListByRawIdsAndNoEmbedding(List<Long> rawIdList) {
        if (CollectionUtils.isEmpty(rawIdList)) {
            return List.of();
        }
        LambdaQueryWrapper<KnowledgeQaSegment> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KnowledgeQaSegment::getYn, YnEnum.Y.getKey())
                .in(KnowledgeQaSegment::getRawId, rawIdList)
                .eq(KnowledgeQaSegment::getHasEmbedding, false);
        return this.list(queryWrapper);
    }

    @Override
    public void deleteByRawId(Long rawId) {
        LambdaQueryWrapper<KnowledgeQaSegment> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KnowledgeQaSegment::getYn, YnEnum.Y.getKey())
                .eq(KnowledgeQaSegment::getRawId, rawId);
        this.remove(queryWrapper);
    }

    @Override
    public List<KnowledgeQaSegment> queryListByDocIds(List<Long> docIds) {
        LambdaQueryWrapper<KnowledgeQaSegment> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KnowledgeQaSegment::getYn, YnEnum.Y.getKey())
                .in(KnowledgeQaSegment::getDocId, docIds);
        return this.list(queryWrapper);
    }

    @Override
    public List<Long> queryQaIdList(Long rawId) {
        LambdaQueryWrapper<KnowledgeQaSegment> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KnowledgeQaSegment::getYn, YnEnum.Y.getKey())
                .eq(KnowledgeQaSegment::getRawId, rawId);
        var dataList = this.list(queryWrapper);
        return dataList.stream()
                .map(KnowledgeQaSegment::getId)
                .toList();
    }

    @Override
    public List<KnowledgeQaSegment> queryListInfoByRawId(Long rawId) {
        LambdaQueryWrapper<KnowledgeQaSegment> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KnowledgeQaSegment::getYn, YnEnum.Y.getKey())
                .eq(KnowledgeQaSegment::getRawId, rawId);
        return this.list(queryWrapper);
    }

}
