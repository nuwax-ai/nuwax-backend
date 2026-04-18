package com.xspaceagi.knowledge.core.infra.respository;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Repository;
import org.springframework.util.CollectionUtils;

import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.xspaceagi.knowledge.core.infra.dao.mapper.KnowledgeRawSegmentMapper;
import com.xspaceagi.knowledge.core.infra.dao.service.KnowledgeRawSegmentService;
import com.xspaceagi.knowledge.core.infra.translator.IKnowledgeRawSegmentTranslator;
import com.xspaceagi.knowledge.core.spec.enums.QaStatusEnum;
import com.xspaceagi.knowledge.domain.model.KnowledgeRawSegmentModel;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeRawSegmentRepository;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.KnowledgeException;
import com.xspaceagi.system.spec.page.PageUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Repository
public class KnowledgeRawSegmentRepository implements IKnowledgeRawSegmentRepository {

    @Resource
    private IKnowledgeRawSegmentTranslator knowledgeRawSegmentTranslator;

    @Resource
    private KnowledgeRawSegmentService knowledgeRawSegmentService;

    @Resource
    private KnowledgeRawSegmentMapper knowledgeRawSegmentMapper;

    @Override
    public List<KnowledgeRawSegmentModel> pageQuery(Map<String, Object> queryMap, List<OrderItem> orderColumns,
                                                    Long startIndex, Long pageSize) {
        var dataList = this.knowledgeRawSegmentMapper.queryList(queryMap,
                orderColumns, startIndex, pageSize);

        return dataList.stream()
                .map(sysUser -> this.knowledgeRawSegmentTranslator.convertToModel(sysUser))
                .toList();
    }

    @Override
    public Long queryTotal(Map<String, Object> queryMap) {
        return this.knowledgeRawSegmentMapper.queryTotal(queryMap);
    }

    @Override
    public KnowledgeRawSegmentModel queryOneInfoById(Long id) {
        var data = this.knowledgeRawSegmentService.queryOneInfoById(id);
        return this.knowledgeRawSegmentTranslator.convertToModel(data);
    }

    @Override
    public List<KnowledgeRawSegmentModel> queryListInfoByIds(List<Long> ids) {

        if (CollectionUtils.isEmpty(ids)) {
            return Collections.emptyList();
        }

        var datas = this.knowledgeRawSegmentService.queryListByIds(ids);

        return datas.stream()
                .map(o -> this.knowledgeRawSegmentTranslator.convertToModel(o))
                .toList();
    }

    @Override
    public List<KnowledgeRawSegmentModel> queryListByDocId(Long docId) {
        var data = this.knowledgeRawSegmentService.queryListByDocId(docId);

        return data.stream()
                .map(o -> this.knowledgeRawSegmentTranslator.convertToModel(o))
                .collect(Collectors.toList());
    }

    @Override
    public void deleteById(Long id) {
        var existObj = this.knowledgeRawSegmentService.getById(id);
        if (existObj == null) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }

        this.knowledgeRawSegmentService.removeById(id);
    }

    @Override
    public void deleteByConfigDocumentId(Long docId) {
        this.knowledgeRawSegmentService.deleteByConfigDocumentId(docId);
    }

    @Override
    public Long updateInfo(KnowledgeRawSegmentModel model, UserContext userContext) {
        var existObj = this.knowledgeRawSegmentService.queryOneInfoById(model.getId());
        if (Objects.isNull(existObj)) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }
        //问答生成的状态,需要重新生成,修改为待生成
        model.setQaStatus(QaStatusEnum.PENDING.getCode());
        model.setCreatorId(null);
        model.setCreatorName(null);
        model.setModified(null);
        model.setModifiedId(userContext.getUserId());
        model.setModifiedName(userContext.getUserName());
        var entity = this.knowledgeRawSegmentTranslator.convertToEntity(model);
        var id = this.knowledgeRawSegmentService.updateInfo(entity);

        return id;
    }

    @Override
    public Long addInfo(KnowledgeRawSegmentModel model, UserContext userContext) {
        model.setId(null);
        model.setCreatorId(userContext.getUserId());
        model.setCreatorName(userContext.getUserName());

        var entity = this.knowledgeRawSegmentTranslator.convertToEntity(model);
        var id = this.knowledgeRawSegmentService.addInfo(entity);

        return id;
    }

    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public void batchUpdateQaStatus(List<KnowledgeRawSegmentModel> segments, UserContext userContext) {

        if (log.isDebugEnabled()) {
            var rawIds = segments.stream().map(segment -> segment.getId()).collect(Collectors.toList());
            log.debug("批量更新问答状态，待更新问答分段ID：{}", rawIds);
        }

        var currentDate = LocalDateTime.now();
        var entityList = segments.stream().map(segment -> {
            var entity = this.knowledgeRawSegmentTranslator.convertToEntity(segment);
            entity.setModified(currentDate);
            entity.setModifiedId(userContext.getUserId());
            entity.setModifiedName(userContext.getUserName());
            return entity;
        }).toList();
        this.knowledgeRawSegmentService.batchUpdateQaStatus(entityList);
    }

    @Override
    public List<KnowledgeRawSegmentModel> queryListForPendingQa(Integer days, Integer pageSize, Integer pageNum) {

        var startIndex = PageUtils.getStartIndex(pageNum, pageSize);
        var endIndex = PageUtils.getEndIndex(pageNum, pageSize);
        var data = this.knowledgeRawSegmentMapper.queryListForPendingQa(days, startIndex, endIndex);
        return data.stream()
                .map(o -> this.knowledgeRawSegmentTranslator.convertToModel(o))
                .toList();
    }

    @Override
    public Long queryCountForPendingQaByDocId(Long docId) {
        return this.knowledgeRawSegmentService.queryCountForPendingQaByDocId(docId);
    }

    @Override
    public List<KnowledgeRawSegmentModel> queryListForPendingQaByDocId(Long docId) {
        var dataList = this.knowledgeRawSegmentService.queryListForPendingQaByDocId(docId);
        return dataList.stream()
                .map(o -> this.knowledgeRawSegmentTranslator.convertToModel(o))
                .toList();
    }

    // ========== 全文检索相关实现 ========== //

    @Override
    public List<KnowledgeRawSegmentModel> queryByKbIdWithPage(Long kbId, Integer pageNum, Integer pageSize) {
        var startIndex = PageUtils.getStartIndex(pageNum, pageSize);
        var endIndex = PageUtils.getEndIndex(pageNum, pageSize);
        
        var dataList = this.knowledgeRawSegmentMapper.queryByKbIdWithPage(kbId, startIndex, endIndex);
        
        return dataList.stream()
                .map(o -> this.knowledgeRawSegmentTranslator.convertToModel(o))
                .toList();
    }

    @Override
    public List<Long> queryAllSegmentIdsByKbId(Long kbId) {
        return this.knowledgeRawSegmentMapper.queryAllSegmentIdsByKbId(kbId);
    }

    @Override
    public Long countByKbId(Long kbId) {
        return this.knowledgeRawSegmentMapper.countByKbId(kbId);
    }

    @Override
    public List<KnowledgeRawSegmentModel> queryUnsyncedSegments(Long kbId, Integer offset, Integer limit) {
        var dataList = this.knowledgeRawSegmentMapper.queryUnsyncedSegments(kbId, offset, limit);
        return dataList.stream()
                .map(knowledgeRawSegmentTranslator::convertToModel)
                .collect(Collectors.toList());
    }

    @Override
    public Long countUnsyncedByKbId(Long kbId) {
        return this.knowledgeRawSegmentMapper.countUnsyncedByKbId(kbId);
    }

    @Override
    public void batchUpdateSyncStatus(List<Long> segmentIds, Integer status) {
        if (CollectionUtils.isEmpty(segmentIds)) {
            return;
        }
        this.knowledgeRawSegmentMapper.batchUpdateSyncStatus(segmentIds, status);
    }

    @Override
    public Long countSyncedByKbId(Long kbId) {
        return this.knowledgeRawSegmentMapper.countSyncedByKbId(kbId);
    }

    @Override
    public List<Long> queryAllSegmentIds(Long kbId) {
        return this.knowledgeRawSegmentMapper.queryAllSegmentIdsByKbId(kbId);
    }
}
