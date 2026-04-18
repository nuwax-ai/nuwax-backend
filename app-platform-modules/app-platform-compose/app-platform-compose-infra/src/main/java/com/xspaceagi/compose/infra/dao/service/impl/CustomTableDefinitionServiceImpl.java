package com.xspaceagi.compose.infra.dao.service.impl;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.compose.infra.dao.entity.CustomTableDefinition;
import com.xspaceagi.compose.infra.dao.mapper.CustomTableDefinitionMapper;
import com.xspaceagi.compose.infra.dao.service.CustomTableDefinitionService;
import com.xspaceagi.system.spec.enums.YnEnum;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.ComposeException;

@Service
public class CustomTableDefinitionServiceImpl extends ServiceImpl<CustomTableDefinitionMapper, CustomTableDefinition>
        implements CustomTableDefinitionService {

    @Autowired
    private CustomTableDefinitionMapper customTableDefinitionMapper;

    @Override
    public List<CustomTableDefinition> queryListByIds(List<Long> ids) {
        LambdaQueryWrapper<CustomTableDefinition> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(CustomTableDefinition::getYn, YnEnum.Y.getKey())
                .in(CustomTableDefinition::getId, ids);
        return this.list(queryWrapper);
    }

    @Override
    public CustomTableDefinition queryOneInfoById(Long id) {
        LambdaQueryWrapper<CustomTableDefinition> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(CustomTableDefinition::getYn, YnEnum.Y.getKey())
                .eq(CustomTableDefinition::getId, id);
        return this.getOne(queryWrapper);
    }

    @Override
    public Long updateInfo(CustomTableDefinition entity) {
        var updateObj = this.getById(entity.getId());

        if (Objects.isNull(updateObj)) {
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }
        entity.setCreated(null);
        entity.setModified(null);
        this.updateById(entity);
        return entity.getId();
    }

    @Override
    public Long addInfo(CustomTableDefinition entity) {
        entity.setId(null);
        entity.setCreated(null);
        entity.setModified(null);
        this.save(entity);
        return entity.getId();
    }

    @Override
    public void deleteById(Long id) {
        var existObj = this.getById(id);
        if (Objects.isNull(existObj)) {
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }
        this.removeById(id);
    }

    @Override
    public List<CustomTableDefinition> pageQuery(Map<String, Object> queryMap, List<OrderItem> orderColumns, Long startIndex, Long pageSize) {
        return customTableDefinitionMapper.queryList(queryMap, orderColumns, startIndex, pageSize);
    }

    @Override
    public Long queryTotal(Map<String, Object> queryMap) {
        return customTableDefinitionMapper.queryTotal(queryMap);
    }

    @Override
    public List<CustomTableDefinition> queryListByCondition(CustomTableDefinition condition) {
        LambdaQueryWrapper<CustomTableDefinition> wrapper = new LambdaQueryWrapper<>();
        
        // 添加租户ID条件（必填）
        wrapper.eq(CustomTableDefinition::getTenantId, condition.getTenantId());
        
        // 添加空间ID条件（可选）
        if (condition.getSpaceId() != null) {
            wrapper.eq(CustomTableDefinition::getSpaceId, condition.getSpaceId());
        }
        
        // 添加表名模糊查询条件（可选）
        if (StringUtils.hasText(condition.getTableName())) {
            wrapper.like(CustomTableDefinition::getTableName, condition.getTableName());
        }
        
        // 添加表描述模糊查询条件（可选）
        if (StringUtils.hasText(condition.getTableDescription())) {
            wrapper.like(CustomTableDefinition::getTableDescription, condition.getTableDescription());
        }
        
        // 添加状态条件（可选）
        if (condition.getStatus() != null) {
            wrapper.eq(CustomTableDefinition::getStatus, condition.getStatus());
        } else {
            // 默认只查询启用状态的表定义
            wrapper.eq(CustomTableDefinition::getStatus, 1);
        }
        
        // 按创建时间降序排序
        wrapper.orderByDesc(CustomTableDefinition::getId);
        
        return this.list(wrapper);
    }

    @Override
    public List<CustomTableDefinition> queryListBySpaceId(Long spaceId) {
        if (spaceId == null) {
            return List.of();
        }
        LambdaQueryWrapper<CustomTableDefinition> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CustomTableDefinition::getSpaceId, spaceId);
        wrapper.eq(CustomTableDefinition::getYn, YnEnum.Y.getKey());
        return this.list(wrapper);
    }
}
