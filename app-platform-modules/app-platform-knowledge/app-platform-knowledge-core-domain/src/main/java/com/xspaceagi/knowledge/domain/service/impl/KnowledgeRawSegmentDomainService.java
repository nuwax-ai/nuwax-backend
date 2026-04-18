package com.xspaceagi.knowledge.domain.service.impl;

import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.xspaceagi.knowledge.domain.model.KnowledgeRawSegmentModel;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeQaSegmentRepository;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeRawSegmentRepository;
import com.xspaceagi.knowledge.domain.service.IKnowledgeFullTextSearchDomainService;
import com.xspaceagi.knowledge.domain.service.IKnowledgeRawSegmentDomainService;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.KnowledgeException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class KnowledgeRawSegmentDomainService implements IKnowledgeRawSegmentDomainService {

    @Resource
    private IKnowledgeRawSegmentRepository knowledgeRawSegmentRepository;

    @Resource
    private IKnowledgeQaSegmentRepository knowledgeQaSegmentRepository;

    @Resource
    private IKnowledgeFullTextSearchDomainService fullTextSearchDomainService;

    @Override
    public KnowledgeRawSegmentModel queryOneInfoById(Long id) {
        return knowledgeRawSegmentRepository.queryOneInfoById(id);
    }

    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public void deleteById(Long id, UserContext userContext) {
        // 删除原始分段
        this.knowledgeRawSegmentRepository.deleteById(id);

        // 根据分段id,删除对应的问答,以及问答的向量化
        this.knowledgeQaSegmentRepository.deleteByRawId(id);

        // 同步删除全文检索数据（在事务内，保证一致性）
        try {
            fullTextSearchDomainService.deleteByRawIds(java.util.Arrays.asList(id), userContext.getTenantId());
            log.info("Delete raw segment full-text OK: rawId={}", id);
        } catch (Exception e) {
            log.error("Delete raw segment full-text failed: rawId={}", id, e);
            // 抛出异常，触发事务回滚
            throw KnowledgeException.build(BizExceptionCodeEnum.knowledgeDeleteSegmentFulltextFailed, e);
        }
    }

    @Override
    public Long updateInfo(KnowledgeRawSegmentModel model, UserContext userContext) {
        var id = this.knowledgeRawSegmentRepository.updateInfo(model, userContext);
        return id;
    }

    @Override
    public Long addInfo(KnowledgeRawSegmentModel model, UserContext userContext) {
        var id = this.knowledgeRawSegmentRepository.addInfo(model, userContext);
        return id;
    }

    @Override
    public List<KnowledgeRawSegmentModel> queryListForPendingQa(Integer days, Integer pageSize, Integer pageNum) {
        return this.knowledgeRawSegmentRepository.queryListForPendingQa(days, pageSize, pageNum);
    }

}
