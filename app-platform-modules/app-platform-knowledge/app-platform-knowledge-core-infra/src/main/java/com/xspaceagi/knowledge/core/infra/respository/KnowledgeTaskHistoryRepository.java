package com.xspaceagi.knowledge.core.infra.respository;

import com.xspaceagi.knowledge.core.infra.dao.service.KnowledgeTaskHistoryService;
import com.xspaceagi.knowledge.core.infra.translator.IKnowledgeTaskHistoryTranslator;
import com.xspaceagi.knowledge.domain.model.KnowledgeTaskHistoryModel;
import com.xspaceagi.knowledge.domain.model.KnowledgeTaskModel;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeTaskHistoryRepository;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.KnowledgeException;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;
import org.springframework.util.CollectionUtils;

import java.util.List;

@Repository
public class KnowledgeTaskHistoryRepository implements IKnowledgeTaskHistoryRepository {

    @Resource
    private KnowledgeTaskHistoryService knowledgeTaskHistoryService;

    @Resource
    private IKnowledgeTaskHistoryTranslator knowledgeTaskHistoryTranslator;

    @Override
    public List<KnowledgeTaskHistoryModel> queryListByIds(List<Long> ids) {
        var data = knowledgeTaskHistoryService.queryListByIds(ids);
        return data.stream()
                .map(knowledgeTaskHistoryTranslator::convertToModel)
                .toList();
    }

    @Override
    public KnowledgeTaskHistoryModel queryOneInfoById(Long id) {
        var data = knowledgeTaskHistoryService.queryOneInfoById(id);
        return knowledgeTaskHistoryTranslator.convertToModel(data);
    }

    @Override
    public Long updateInfo(KnowledgeTaskHistoryModel model, UserContext userContext) {
        var existObj = this.knowledgeTaskHistoryService.getById(model.getId());
        if (existObj == null) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }

        model.setCreatorId(null);
        model.setCreatorName(null);
        model.setModified(null);
        var entity = this.knowledgeTaskHistoryTranslator.convertToEntity(model);
        var id = this.knowledgeTaskHistoryService.updateInfo(entity);

        return id;
    }

    @Override
    public Long addInfo(KnowledgeTaskHistoryModel model, UserContext userContext) {
        model.setId(null);
        model.setCreatorId(userContext.getUserId());
        model.setCreatorName(userContext.getUserName());


        var entity = this.knowledgeTaskHistoryTranslator.convertToEntity(model);
        var id = this.knowledgeTaskHistoryService.addInfo(entity);

        return id;
    }

    @Override
    public void batchArchiveInfo(List<KnowledgeTaskModel> modelList, UserContext userContext) {


        if (!CollectionUtils.isEmpty(modelList)) {

            //清空主键id
            modelList.forEach(item -> {
                item.setId(null);
            });
            var entityList = modelList.stream()
                    .map(KnowledgeTaskHistoryModel::convertFrom)
                    .peek(item -> item.setId(null))
                    .map(item -> this.knowledgeTaskHistoryTranslator.convertToEntity(item))
                    .toList();


            this.knowledgeTaskHistoryService.saveBatch(entityList);
        }


    }

    @Override
    public void deleteById(Long id, UserContext userContext) {

        var existObj = this.knowledgeTaskHistoryService.getById(id);
        if (existObj == null) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }


        this.knowledgeTaskHistoryService.removeById(id);

    }
}
