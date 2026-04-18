package com.xspaceagi.knowledge.core.infra.respository;

import com.xspaceagi.knowledge.core.infra.dao.mapper.KnowledgeTaskHistoryMapper;
import com.xspaceagi.knowledge.core.infra.dao.mapper.KnowledgeTaskMapper;
import com.xspaceagi.knowledge.core.infra.dao.service.KnowledgeTaskService;
import com.xspaceagi.knowledge.core.infra.translator.IKnowledgeTaskTranslator;
import com.xspaceagi.knowledge.domain.model.KnowledgeTaskModel;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeTaskRepository;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.KnowledgeException;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class KnowledgeTaskRepository implements IKnowledgeTaskRepository {

    @Resource
    private KnowledgeTaskService knowledgeTaskService;

    @Resource
    private IKnowledgeTaskTranslator knowledgeTaskTranslator;


    @Resource
    private KnowledgeTaskMapper knowledgeTaskMapper;

    @Resource
    private KnowledgeTaskHistoryMapper knowledgeTaskHistoryMapper;

    @Override
    public List<KnowledgeTaskModel> queryListForRetryByDays(Integer days) {
        var data = knowledgeTaskMapper.queryListForRetryByDays(days);
        return data.stream()
                .map(knowledgeTaskTranslator::convertToModel)
                .toList();
    }

    @Override
    public List<KnowledgeTaskModel> queryListForArchiveByDaysAndSuccess(Integer days) {

        var data = knowledgeTaskMapper.queryListForArchiveByDaysAndSuccess(days);
        return data.stream()
                .map(knowledgeTaskTranslator::convertToModel)
                .toList();


    }

    @Override
    public List<KnowledgeTaskModel> queryListByDocIds(List<Long> docIds) {
        var data = knowledgeTaskService.queryListByDocIds(docIds);
        return data.stream()
                .map(knowledgeTaskTranslator::convertToModel)
                .toList();
    }

    @Override
    public List<KnowledgeTaskModel> queryListByIds(List<Long> ids) {
        var data = knowledgeTaskService.queryListByIds(ids);
        return data.stream()
                .map(knowledgeTaskTranslator::convertToModel)
                .toList();
    }


    @Override
    public KnowledgeTaskModel queryOneInfoById(Long id) {
        var data = knowledgeTaskService.queryOneInfoById(id);
        return knowledgeTaskTranslator.convertToModel(data);
    }

    @Override
    public KnowledgeTaskModel queryOneByDocId(Long docId) {
        var data = knowledgeTaskService.queryOneByDocId(docId);
        return knowledgeTaskTranslator.convertToModel(data);
    }

    @Override
    public Long updateInfo(KnowledgeTaskModel model, UserContext userContext) {
        var existObj = this.knowledgeTaskService.getById(model.getId());
        if (existObj == null) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }

        model.setCreatorId(null);
        model.setCreatorName(null);
        model.setModified(null);
        var entity = this.knowledgeTaskTranslator.convertToEntity(model);
        var id = this.knowledgeTaskService.updateInfo(entity);

        return id;
    }

    @Override
    public Long addInfo(KnowledgeTaskModel model, UserContext userContext) {
        model.setId(null);
        model.setCreatorId(userContext.getUserId());
        model.setCreatorName(userContext.getUserName());


        var entity = this.knowledgeTaskTranslator.convertToEntity(model);
        var id = this.knowledgeTaskService.addInfo(entity);

        return id;
    }

    @Override
    public void deleteById(Long id, UserContext userContext) {

        var existObj = this.knowledgeTaskService.getById(id);
        if (existObj == null) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }


        this.knowledgeTaskService.removeById(id);

    }

    @Override
    public void deleteByDocId(Long docId) {

        this.knowledgeTaskService.deleteByDocId(docId);
    }

    @Override
    public void incrementRetryCount(Long id, UserContext userContext) {

        this.knowledgeTaskMapper.incrementRetryCount(id);
    }

    @Override
    public void deleteByDocIds(List<Long> docIds) {
        this.knowledgeTaskService.deleteByDocIds(docIds);
    }


}
