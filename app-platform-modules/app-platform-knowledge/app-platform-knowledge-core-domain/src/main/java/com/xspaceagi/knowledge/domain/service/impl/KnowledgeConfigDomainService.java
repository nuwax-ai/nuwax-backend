package com.xspaceagi.knowledge.domain.service.impl;

import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.xspaceagi.agent.core.adapter.application.ModelApplicationService;
import com.xspaceagi.knowledge.domain.model.KnowledgeConfigModel;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeConfigRepository;
import com.xspaceagi.knowledge.domain.service.IKnowledgeConfigDomainService;
import com.xspaceagi.knowledge.domain.service.IKnowledgeFullTextSearchDomainService;
import com.xspaceagi.knowledge.domain.vectordb.VectorDBService;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.KnowledgeException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Slf4j
@Service
public class KnowledgeConfigDomainService implements IKnowledgeConfigDomainService {

    @Resource
    private IKnowledgeConfigRepository knowledgeConfigRepository;

    @Resource
    private VectorDBService vectorDBService;

    @Resource
    private ModelApplicationService modelApplicationService;

    @Resource
    private IKnowledgeFullTextSearchDomainService fullTextSearchDomainService;

    @Override
    public KnowledgeConfigModel queryOneInfoById(Long id) {

        var data = this.knowledgeConfigRepository.queryOneInfoById(id);

        if (Objects.nonNull(data) && Objects.isNull(data.getEmbeddingModelId())) {
            var defaultEmbedModel = modelApplicationService.getDefaultEmbedModel();
            if (Objects.nonNull(defaultEmbedModel)) {
                data.setEmbeddingModelId(defaultEmbedModel.getId());
            }
        }

        return data;

    }

    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public void deleteById(Long id, UserContext userContext) {

        this.knowledgeConfigRepository.deleteById(id);

        // 删除 Milvus 中的语义检索 Collection
        this.vectorDBService.deleteCollection(id);

        // 删除 Quickwit 中的全文检索数据（在事务内，保证一致性）
        try {
            fullTextSearchDomainService.deleteByKbId(id, userContext.getTenantId());
            log.info("Delete KB full-text data OK: kbId={}", id);
        } catch (Exception e) {
            log.error("Delete KB full-text data failed: kbId={}", id, e);
            // 抛出异常，触发事务回滚
            throw KnowledgeException.build(BizExceptionCodeEnum.knowledgeDeleteKbFulltextFailed, e);
        }

    }

    @Override
    public Long updateInfo(KnowledgeConfigModel model, UserContext userContext) {
        return this.knowledgeConfigRepository.updateInfo(model, userContext);
    }

    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public Long addInfo(KnowledgeConfigModel model, UserContext userContext) {

        var id = this.knowledgeConfigRepository.addInfo(model, userContext);
        // 初始化向量数据库
        var embeddingModelId = model.getEmbeddingModelId();
        vectorDBService.initAndCheckCollection(id, embeddingModelId);

        return id;
    }

    @Override
    public List<KnowledgeConfigModel> queryListBySpaceId(Long spaceId) {
        return knowledgeConfigRepository.queryListBySpaceId(spaceId);
    }

    @Override
    public Long queryTotalFileSize(Long kbId) {
        return this.knowledgeConfigRepository.queryTotalFileSize(kbId);
    }

    @Override
    public List<KnowledgeConfigModel> queryListByIds(List<Long> kbIds) {
        return this.knowledgeConfigRepository.queryListByIds(kbIds);
    }

    @Override
    public Long countTotalKnowledge(Long userId) {
        return this.knowledgeConfigRepository.countTotalKnowledge(userId);
    }
}
