package com.xspaceagi.knowledge.core.infra.dao.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.knowledge.core.infra.dao.entity.KnowledgeConfig;
import com.xspaceagi.knowledge.core.infra.dao.mapper.KnowledgeConfigMapper;
import com.xspaceagi.knowledge.core.infra.dao.service.KnowledgeConfigService;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.enums.YnEnum;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.KnowledgeException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * @author soddy
 * @description 针对表【knowledge_config(知识库表)】的数据库操作Service实现
 * @createDate 2025-01-21 15:33:38
 */
@Service
public class KnowledgeConfigServiceImpl extends ServiceImpl<KnowledgeConfigMapper, KnowledgeConfig>
        implements KnowledgeConfigService {

    @Override
    public List<KnowledgeConfig> queryListByIds(List<Long> ids) {
        if (Objects.isNull(ids) || ids.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<KnowledgeConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KnowledgeConfig::getYn, YnEnum.Y.getKey())
                .in(KnowledgeConfig::getId, ids);
        return this.list(queryWrapper);
    }

    @Override
    public KnowledgeConfig queryOneInfoById(Long id) {
        LambdaQueryWrapper<KnowledgeConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KnowledgeConfig::getYn, YnEnum.Y.getKey())
                .eq(KnowledgeConfig::getId, id);
        return this.getOne(queryWrapper);

    }

    @Override
    public Long updateInfo(KnowledgeConfig entity) {
        var updateObj = this.getById(entity.getId());

        if (Objects.isNull(updateObj)) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }
        entity.setCreated(null);
        entity.setModified(null);

        this.updateById(entity);

        // 如果 workflowId 传入为 null，需要显式将数据库字段置为 NULL
        if (entity.getWorkflowId() == null) {
            this.lambdaUpdate()
                    .set(KnowledgeConfig::getWorkflowId, null)
                    .eq(KnowledgeConfig::getId, entity.getId())
                    .update();
        }

        return entity.getId();
    }

    @Override
    public Long addInfo(KnowledgeConfig entity) {
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
    public List<KnowledgeConfig> queryListBySpaceId(Long spaceId) {
        LambdaQueryWrapper<KnowledgeConfig> queryWrapper = Wrappers.lambdaQuery();
        queryWrapper.eq(KnowledgeConfig::getYn, YnEnum.Y.getKey())
                .eq(KnowledgeConfig::getSpaceId, spaceId);
        return this.list(queryWrapper);
    }

    @Override
    public void updateLatestModifyTime(Long id, UserContext userContext) {
        var existObj = this.getById(id);
        if (Objects.isNull(existObj)) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }
        var updateObj = new KnowledgeConfig();
        updateObj.setId(id);
        updateObj.setModified(LocalDateTime.now());
        updateObj.setModifiedId(userContext.getUserId());
        updateObj.setModifiedName(userContext.getUserName());
        this.updateById(updateObj);
    }

    @Override
    public void updateKnowledgeConfigFileSize(Long kbId, Long fileSize) {
        var updateObj = new KnowledgeConfig();
        updateObj.setId(kbId);
        updateObj.setFileSize(fileSize);
        this.updateById(updateObj);
    }
}
