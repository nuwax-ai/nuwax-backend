package com.xspaceagi.compose.infra.respository;

import com.xspaceagi.compose.domain.model.CustomFieldDefinitionModel;
import com.xspaceagi.compose.domain.repository.ICustomFieldDefinitionRepository;

import com.xspaceagi.compose.infra.dao.mapper.CustomFieldDefinitionMapper;
import com.xspaceagi.compose.infra.dao.service.CustomFieldDefinitionService;
import com.xspaceagi.compose.infra.translator.CustomFieldDefinitionTranslator;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.ComposeException;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Repository
public class CustomFieldDefinitionRepository implements ICustomFieldDefinitionRepository {

    @Resource
    private CustomFieldDefinitionService customFieldDefinitionService;

    @Resource
    private CustomFieldDefinitionMapper customFieldDefinitionMapper;

    @Resource
    private CustomFieldDefinitionTranslator customFieldDefinitionTranslator;

    @Override
    public List<CustomFieldDefinitionModel> queryListByIds(List<Long> ids) {
        return customFieldDefinitionService.queryListByIds(ids)
                .stream()
                .map(customFieldDefinitionTranslator::convertToModel)
                .collect(Collectors.toList());
    }

    @Override
    public CustomFieldDefinitionModel queryOneInfoById(Long id) {
        return customFieldDefinitionTranslator.convertToModel(
                customFieldDefinitionService.queryOneInfoById(id));
    }

    @Override
    public List<CustomFieldDefinitionModel> queryListByTableId(Long tableId) {
        return customFieldDefinitionService.queryListByTableId(tableId)
                .stream()
                .map(customFieldDefinitionTranslator::convertToModel)
                .collect(Collectors.toList());
    }

    @Override
    public List<CustomFieldDefinitionModel> queryListByTableIds(List<Long> tableIds) {
        return customFieldDefinitionService.queryListByTableIds(tableIds)
                .stream()
                .map(customFieldDefinitionTranslator::convertToModel)
                .collect(Collectors.toList());
    }

    @Override
    public void batchAddInfo(List<CustomFieldDefinitionModel> modelList, UserContext userContext) {

        modelList.forEach(entity -> {
            entity.setCreated(null);
            entity.setModified(null);
            entity.setModifiedId(userContext.getUserId());
            entity.setModifiedName(userContext.getUserName());
        });

        var entityList = modelList.stream()
                .map(customFieldDefinitionTranslator::convertToEntity)
                .collect(Collectors.toList());

        customFieldDefinitionService.batchAddInfo(entityList);
    }

    @Override
    public void batchUpdateInfo(List<CustomFieldDefinitionModel> modelList, UserContext userContext) {

        modelList.forEach(entity -> {
            entity.setCreated(null);
            entity.setModified(null);
            entity.setModifiedId(userContext.getUserId());
            entity.setModifiedName(userContext.getUserName());
        });

        var entityList = modelList.stream()
                .map(customFieldDefinitionTranslator::convertToEntity)
                .collect(Collectors.toList());

        customFieldDefinitionService.batchUpdateInfo(entityList);
    }

    @Override
    public void deleteById(Long id, UserContext userContext) {
        var existOne = customFieldDefinitionService.queryOneInfoById(id);
        if (Objects.isNull(existOne)) {
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }

        customFieldDefinitionService.deleteById(id);
    }

    @Override
    public void deleteByTableId(Long tableId) {

        var existCount = customFieldDefinitionService.queryCountByTableId(tableId);
        if (existCount == 0) {
            log.info("No columns to drop, tableId={}", tableId);
        } else {
            customFieldDefinitionService.deleteByTableId(tableId);
        }

    }
}
