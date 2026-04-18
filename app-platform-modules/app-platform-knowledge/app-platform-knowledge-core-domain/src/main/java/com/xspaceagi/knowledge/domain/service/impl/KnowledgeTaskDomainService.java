package com.xspaceagi.knowledge.domain.service.impl;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.xspaceagi.knowledge.core.spec.enums.KnowledgeTaskRunTypeEnum;
import com.xspaceagi.knowledge.core.spec.enums.KnowledgeTaskStageStatusEnum;
import com.xspaceagi.knowledge.domain.dto.task.AutoRecordTask;
import com.xspaceagi.knowledge.domain.model.KnowledgeTaskModel;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeDocumentRepository;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeTaskHistoryRepository;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeTaskRepository;
import com.xspaceagi.knowledge.domain.service.IKnowledgeTaskDomainService;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.KnowledgeException;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class KnowledgeTaskDomainService implements IKnowledgeTaskDomainService {

    @Resource
    private IKnowledgeTaskHistoryRepository knowledgeTaskHistoryRepository;


    @Resource
    private IKnowledgeTaskRepository knowledgeTaskRepository;


    @Resource
    private IKnowledgeDocumentRepository knowledgeDocumentRepository;


    @Override
    public List<KnowledgeTaskModel> queryListByDocIds(List<Long> docIds) {
        if (CollectionUtils.isEmpty(docIds)) {
            throw KnowledgeException.build(BizExceptionCodeEnum.knowledgeFileIdNotFound);
        }

        return this.knowledgeTaskRepository.queryListByDocIds(docIds);

    }

    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public void changeTaskStatus(AutoRecordTask autoRecordTask, KnowledgeTaskRunTypeEnum runType, UserContext userContext) {

        var docId = autoRecordTask.getDocId();
        var spaceId = autoRecordTask.getSpaceId();
        var kbId = autoRecordTask.getKbId();

        KnowledgeTaskModel taskExistModel = this.knowledgeTaskRepository.queryOneByDocId(docId);

        if (Objects.isNull(taskExistModel)) {
            //新增重试记录


            taskExistModel = KnowledgeTaskModel.createEmptyTaskModel(docId, runType, userContext);

            taskExistModel.setSpaceId(spaceId);
            taskExistModel.setKbId(kbId);


        } else {
            //修改重试记录状态

        }

        taskExistModel.setName(runType.getDesc());
        taskExistModel.setType(runType.getType());

        //重置任务状态
        taskExistModel.setStatus(KnowledgeTaskStageStatusEnum.INIT.getStatus());


        //根据主键id,判断是新增,还是修改
        if (Objects.isNull(taskExistModel.getId())) {
            //新增
            this.knowledgeTaskRepository.addInfo(taskExistModel, userContext);
        } else {
            //修改
            this.knowledgeTaskRepository.updateInfo(taskExistModel, userContext);
        }

    }

    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public Long createNewTask(AutoRecordTask autoRecordTask, KnowledgeTaskRunTypeEnum runType, UserContext userContext) {
        var docId = autoRecordTask.getDocId();
        var spaceId = autoRecordTask.getSpaceId();
        var kbId = autoRecordTask.getKbId();

        //新增重试记录
        KnowledgeTaskModel taskExistModel = KnowledgeTaskModel.createEmptyTaskModel(docId, runType, userContext);

        taskExistModel.setSpaceId(spaceId);
        taskExistModel.setKbId(kbId);
        taskExistModel.setName(runType.getDesc());
        taskExistModel.setType(runType.getType());

        //重置任务状态
        taskExistModel.setStatus(KnowledgeTaskStageStatusEnum.INIT.getStatus());

        //新增
        return this.knowledgeTaskRepository.addInfo(taskExistModel, userContext);

    }

    @Override
    public void deleteByDocIds(List<Long> docIds) {
        this.knowledgeTaskRepository.deleteByDocIds(docIds);
    }


}
