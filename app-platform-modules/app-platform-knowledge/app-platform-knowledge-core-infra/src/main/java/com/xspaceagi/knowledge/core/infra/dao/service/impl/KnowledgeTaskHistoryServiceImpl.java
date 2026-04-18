package com.xspaceagi.knowledge.core.infra.dao.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.knowledge.core.infra.dao.entity.KnowledgeTaskHistory;
import com.xspaceagi.knowledge.core.infra.dao.mapper.KnowledgeTaskHistoryMapper;
import com.xspaceagi.knowledge.core.infra.dao.service.KnowledgeTaskHistoryService;
import com.xspaceagi.system.spec.enums.YnEnum;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.KnowledgeException;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * @author soddy
 * @description 针对表【knowledge_task_history(知识库-定时任务-历史)】的数据库操作Service实现
 * @createDate 2025-02-21 11:02:01
 */
@Service
public class KnowledgeTaskHistoryServiceImpl extends ServiceImpl<KnowledgeTaskHistoryMapper, KnowledgeTaskHistory>
        implements KnowledgeTaskHistoryService {


    @Resource
    private KnowledgeTaskHistoryMapper knowledgeTaskHistoryMapper;

    @Override
    public List<KnowledgeTaskHistory> queryListByIds(List<Long> ids) {
        LambdaQueryWrapper<KnowledgeTaskHistory> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KnowledgeTaskHistory::getYn, YnEnum.Y.getKey())
                .in(KnowledgeTaskHistory::getId, ids);
        return this.list(queryWrapper);
    }


    @Override
    public KnowledgeTaskHistory queryOneInfoById(Long id) {
        LambdaQueryWrapper<KnowledgeTaskHistory> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KnowledgeTaskHistory::getYn, YnEnum.Y.getKey())
                .eq(KnowledgeTaskHistory::getId, id);
        return this.getOne(queryWrapper);
    }

    @Override
    public Long updateInfo(KnowledgeTaskHistory entity) {
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
    public Long addInfo(KnowledgeTaskHistory entity) {
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
}




