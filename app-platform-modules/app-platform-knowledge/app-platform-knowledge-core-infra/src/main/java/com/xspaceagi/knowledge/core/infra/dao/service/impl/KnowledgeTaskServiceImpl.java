package com.xspaceagi.knowledge.core.infra.dao.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.knowledge.core.infra.dao.entity.KnowledgeTask;
import com.xspaceagi.knowledge.core.infra.dao.mapper.KnowledgeTaskMapper;
import com.xspaceagi.knowledge.core.infra.dao.service.KnowledgeTaskService;
import com.xspaceagi.system.spec.enums.YnEnum;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.KnowledgeException;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Objects;

/**
 * @author soddy
 * @description 针对表【knowledge_task(知识库-定时任务)】的数据库操作Service实现
 * @createDate 2025-02-21 11:02:01
 */
@Service
public class KnowledgeTaskServiceImpl extends ServiceImpl<KnowledgeTaskMapper, KnowledgeTask>
        implements KnowledgeTaskService {

    @Resource
    private KnowledgeTaskMapper knowledgeTaskMapper;

    @Override
    public List<KnowledgeTask> queryListByDocIds(List<Long> docIds) {
        LambdaQueryWrapper<KnowledgeTask> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KnowledgeTask::getYn, YnEnum.Y.getKey())
                .in(KnowledgeTask::getDocId, docIds);
        return this.list(queryWrapper);
    }

    @Override
    public List<KnowledgeTask> queryListByIds(List<Long> ids) {
        LambdaQueryWrapper<KnowledgeTask> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KnowledgeTask::getYn, YnEnum.Y.getKey())
                .in(KnowledgeTask::getId, ids);
        return this.list(queryWrapper);
    }

    @Override
    public KnowledgeTask queryOneInfoById(Long id) {
        LambdaQueryWrapper<KnowledgeTask> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KnowledgeTask::getYn, YnEnum.Y.getKey())
                .eq(KnowledgeTask::getId, id);
        return this.getOne(queryWrapper);
    }

    @Override
    public KnowledgeTask queryOneByDocId(Long docId) {
        LambdaQueryWrapper<KnowledgeTask> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KnowledgeTask::getYn, YnEnum.Y.getKey())
                .eq(KnowledgeTask::getDocId, docId);
        var list = this.list(queryWrapper);
        if (!list.isEmpty()) {
            return list.get(0);
        } else {
            return null;
        }
    }

    @Override
    public Long updateInfo(KnowledgeTask entity) {
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
    public Long addInfo(KnowledgeTask entity) {
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
    public void deleteByDocId(Long docId) {
        LambdaQueryWrapper<KnowledgeTask> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KnowledgeTask::getDocId, docId);

        this.remove(queryWrapper);
    }

    @Override
    public void deleteByDocIds(List<Long> docIds) {
        if (CollectionUtils.isEmpty(docIds)) {
            return;
        }
        LambdaQueryWrapper<KnowledgeTask> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(KnowledgeTask::getDocId, docIds);

        this.remove(queryWrapper);
    }
}
