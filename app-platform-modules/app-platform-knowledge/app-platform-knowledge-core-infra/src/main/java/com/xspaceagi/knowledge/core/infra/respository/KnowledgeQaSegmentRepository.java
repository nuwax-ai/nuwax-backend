package com.xspaceagi.knowledge.core.infra.respository;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Repository;
import org.springframework.util.CollectionUtils;

import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.xspaceagi.knowledge.core.infra.dao.entity.KnowledgeQaSegment;
import com.xspaceagi.knowledge.core.infra.dao.mapper.KnowledgeQaSegmentMapper;
import com.xspaceagi.knowledge.core.infra.dao.service.KnowledgeQaSegmentService;
import com.xspaceagi.knowledge.core.infra.dao.service.KnowledgeRawSegmentService;
import com.xspaceagi.knowledge.core.infra.translator.IKnowledgeQaSegmentTranslator;
import com.xspaceagi.knowledge.domain.dto.EmbeddingStatusDto;
import com.xspaceagi.knowledge.domain.model.KnowledgeQaSegmentModel;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeQaSegmentRepository;
import com.xspaceagi.knowledge.domain.vectordb.VectorDBService;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.KnowledgeException;
import com.xspaceagi.system.spec.page.PageUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Repository
public class KnowledgeQaSegmentRepository implements IKnowledgeQaSegmentRepository {

    @Resource
    private IKnowledgeQaSegmentTranslator knowledgeQaSegmentTranslator;

    @Resource
    private KnowledgeQaSegmentService knowledgeQaSegmentService;

    @Resource
    private KnowledgeQaSegmentMapper knowledgeQaSegmentMapper;

    @Resource
    private KnowledgeRawSegmentService knowledgeRawSegmentService;
    @Resource
    private VectorDBService vectorDBService;


    @Override
    public List<KnowledgeQaSegmentModel> pageQuery(Map<String, Object> queryMap, List<OrderItem> orderColumns,
            Long startIndex, Long pageSize) {
        var dataList = this.knowledgeQaSegmentMapper.queryList(queryMap,
                orderColumns, startIndex, pageSize);
        var ans = dataList.stream()
                .map(sysUser -> this.knowledgeQaSegmentTranslator.convertToModel(sysUser))
                .collect(Collectors.toList());

        return ans;
    }

    @Override
    public Long queryTotal(Map<String, Object> queryMap) {
        return this.knowledgeQaSegmentMapper.queryTotal(queryMap);
    }

    @Override
    public KnowledgeQaSegmentModel queryOneInfoById(Long id) {

        var data = this.knowledgeQaSegmentService.getById(id);
        var ans = this.knowledgeQaSegmentTranslator.convertToModel(data);
        return ans;
    }

    @Override
    public List<KnowledgeQaSegmentModel> queryListByIds(List<Long> ids) {
        var data = this.knowledgeQaSegmentService.queryListByIds(ids);
        return data.stream()
                .map(o -> this.knowledgeQaSegmentTranslator.convertToModel(o))
                .toList();
    }

    @Override
    public List<KnowledgeQaSegmentModel> queryListByDocIdAndNoEmbedding(Long docId) {
        var data = this.knowledgeQaSegmentService.queryListByDocIdAndNoEmbedding(docId);

        return data.stream()
                .map(item -> this.knowledgeQaSegmentTranslator.convertToModel(item))
                .toList();
    }

    @Override
    public Long queryCountByDocIdAndNoEmbedding(Long docId) {
        return this.knowledgeQaSegmentService.queryCountByDocIdAndNoEmbedding(docId);
    }

    @Override
    public void deleteById(Long id) {
        var existObj = this.knowledgeQaSegmentService.getById(id);
        if (existObj == null) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }

        this.knowledgeQaSegmentService.removeById(id);
    }

    @Override
    public void deleteByDocumentId(Long docId) {

        this.knowledgeQaSegmentService.deleteByConfigDocumentId(docId);

    }

    @Override
    public Long updateInfo(KnowledgeQaSegmentModel model, UserContext userContext) {
        var qaId = model.getId();
        var existObj = this.knowledgeQaSegmentService.queryOneInfoById(qaId);
        if (Objects.isNull(existObj)) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }

        model.setCreatorId(null);
        model.setCreatorName(null);
        model.setModified(null);
        model.setModifiedId(userContext.getUserId());
        model.setModifiedName(userContext.getUserName());
        var entity = this.knowledgeQaSegmentTranslator.convertToEntity(model);
        var id = this.knowledgeQaSegmentService.updateInfo(entity);

        return id;
    }

    @Override
    public Long addInfo(KnowledgeQaSegmentModel model, UserContext userContext) {
        model.setId(null);
        model.setCreatorId(userContext.getUserId());
        model.setCreatorName(userContext.getUserName());

        var entity = this.knowledgeQaSegmentTranslator.convertToEntity(model);
        var id = this.knowledgeQaSegmentService.addInfo(entity);

        model.setId(id);
        return id;
    }

    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public List<Long> batchAddInfo(List<KnowledgeQaSegmentModel> modelList, UserContext userContext) {
        for (var model : modelList) {
            model.setId(null);
            model.setCreatorId(userContext.getUserId());
            model.setCreatorName(userContext.getUserName());

        }

        var list = modelList.stream()
                .map(item -> this.knowledgeQaSegmentTranslator.convertToEntity(item))
                .toList();

        if (!CollectionUtils.isEmpty(list)) {
            this.knowledgeQaSegmentService.batchAddInfo(list);
        }
        //获取主键ids
        var ids = list.stream().map(KnowledgeQaSegment::getId).collect(Collectors.toList());
        return ids;
    }

    @Override
    public EmbeddingStatusDto queryEmbeddingStatus(Long docId) {
        return this.knowledgeQaSegmentMapper.queryEmbeddingStatus(docId);
    }

    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public void batchChangeEmbeddingStatus(List<Long> ids, Boolean hasEmbedding, UserContext userContext) {
        var updateModels = ids.stream()
                .map(id -> {
                    KnowledgeQaSegment model = new KnowledgeQaSegment();
                    model.setId(id);
                    model.setHasEmbedding(hasEmbedding);
                    model.setModifiedId(userContext.getUserId());
                    model.setModifiedName(userContext.getUserName());
                    return model;
                })
                .toList();

        this.knowledgeQaSegmentService.updateBatchById(updateModels);
    }

    @Override
    public List<KnowledgeQaSegmentModel> queryListByRawIdsAndNoEmbedding(List<Long> rawIdList) {
        var data = this.knowledgeQaSegmentService.queryListByRawIdsAndNoEmbedding(rawIdList);
        return data.stream()
                .map(item -> this.knowledgeQaSegmentTranslator.convertToModel(item))
                .toList();
    }

    @Override
    public List<KnowledgeQaSegmentModel> queryListForEmbeddingQaAndEmbeddings(Integer days, Integer pageSize,
            Integer pageNum) {
        var startIndex = PageUtils.getStartIndex(pageNum, pageSize);
        var endIndex = PageUtils.getEndIndex(pageNum, pageSize);
        var data = this.knowledgeQaSegmentMapper.queryListForEmbeddingQaAndEmbeddings(days, startIndex, endIndex);
        return data.stream()
                .map(item -> this.knowledgeQaSegmentTranslator.convertToModel(item))
                .toList();
    }

    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public void deleteByRawId(Long rawId) {

        var rawSegment = this.knowledgeRawSegmentService.queryOneInfoById(rawId);
        if (rawSegment == null) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }

        if (log.isDebugEnabled()) {
            log.debug("deleteByRawId rawId:{}", rawId);
        }
        this.knowledgeQaSegmentService.deleteByRawId(rawId);
        // var docId = rawSegment.getDocId();
        // var qaIds = this.knowledgeQaSegmentService.queryQaIdList(rawId);
        // //删除向量数据库中的问答
        // this.vectorDBService.removeEmbeddingQaIds(docId, qaIds);
    }

    @Override
    public List<KnowledgeQaSegmentModel> queryListInfoByRawId(Long rawId) {
        var data = this.knowledgeQaSegmentService.queryListInfoByRawId(rawId);
        return data.stream()
                .map(item -> this.knowledgeQaSegmentTranslator.convertToModel(item))
                .toList();
    }

    @Override
    public List<Long> queryQaIdList(Long rawId) {
        return this.knowledgeQaSegmentService.queryQaIdList(rawId);
    }

    @Override
    public List<KnowledgeQaSegmentModel> queryListForEmbeddingQaAndEmbeddingsAndRawIdIsNull(Integer days,
            Integer pageSize, Integer pageNum) {
        var startIndex = PageUtils.getStartIndex(pageNum, pageSize);
        var endIndex = PageUtils.getEndIndex(pageNum, pageSize);
        var data = this.knowledgeQaSegmentMapper.queryListForEmbeddingQaAndEmbeddingsAndRawIdIsNull(days, startIndex,
                endIndex);
        return data.stream()
                .map(item -> this.knowledgeQaSegmentTranslator.convertToModel(item))
                .toList();
    }
}
