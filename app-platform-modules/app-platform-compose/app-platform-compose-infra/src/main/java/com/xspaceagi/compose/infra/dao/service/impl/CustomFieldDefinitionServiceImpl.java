package com.xspaceagi.compose.infra.dao.service.impl;

import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.compose.infra.dao.entity.CustomFieldDefinition;
import com.xspaceagi.compose.infra.dao.mapper.CustomFieldDefinitionMapper;
import com.xspaceagi.compose.infra.dao.service.CustomFieldDefinitionService;
import com.xspaceagi.system.spec.enums.YnEnum;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.ComposeException;

@Service
public class CustomFieldDefinitionServiceImpl extends ServiceImpl<CustomFieldDefinitionMapper, CustomFieldDefinition>
        implements CustomFieldDefinitionService {

    @Autowired
    private CustomFieldDefinitionMapper customFieldDefinitionMapper;

    @Override
    public List<CustomFieldDefinition> queryListByIds(List<Long> ids) {
        LambdaQueryWrapper<CustomFieldDefinition> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(CustomFieldDefinition::getYn, YnEnum.Y.getKey())
                .in(CustomFieldDefinition::getId, ids);
        return this.list(queryWrapper);
    }

    @Override
    public CustomFieldDefinition queryOneInfoById(Long id) {
        LambdaQueryWrapper<CustomFieldDefinition> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(CustomFieldDefinition::getYn, YnEnum.Y.getKey())
                .eq(CustomFieldDefinition::getId, id);
        return this.getOne(queryWrapper);
    }

    @Override
    public Long updateInfo(CustomFieldDefinition entity) {
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
    public Long addInfo(CustomFieldDefinition entity) {
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
    public List<CustomFieldDefinition> queryListByTableId(Long tableId) {
        LambdaQueryWrapper<CustomFieldDefinition> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(CustomFieldDefinition::getYn, YnEnum.Y.getKey())
                .eq(CustomFieldDefinition::getTableId, tableId);
        return this.list(queryWrapper);
    }

    @Override
    public List<CustomFieldDefinition> queryListByTableIds(List<Long> tableIds) {
        LambdaQueryWrapper<CustomFieldDefinition> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(CustomFieldDefinition::getYn, YnEnum.Y.getKey())
                .in(CustomFieldDefinition::getTableId, tableIds);
        return this.list(queryWrapper);
    }

    @Override
    public void batchAddInfo(List<CustomFieldDefinition> entityList) {
        entityList.forEach(entity -> {
            entity.setId(null);
            entity.setCreated(null);
            entity.setModified(null);
        });
        this.saveBatch(entityList);
    }

    @Override
    public void batchUpdateInfo(List<CustomFieldDefinition> entityList) {
        entityList.forEach(entity -> {
            entity.setModified(null);
        });
        this.updateBatchById(entityList);
    }

    @Override
    public void deleteByTableId(Long tableId) {
        LambdaQueryWrapper<CustomFieldDefinition> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(CustomFieldDefinition::getTableId, tableId);
        this.remove(queryWrapper);
    }

    @Override
    public Long queryCountByTableId(Long tableId) {
        LambdaQueryWrapper<CustomFieldDefinition> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(CustomFieldDefinition::getTableId, tableId);
        return this.count(queryWrapper);
    }
}
