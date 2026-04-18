package com.xspaceagi.system.domain.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xspaceagi.system.domain.service.SysDataPermissionDomainService;
import com.xspaceagi.system.infra.dao.entity.SysDataPermission;
import com.xspaceagi.system.infra.dao.service.SysDataPermissionService;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.enums.PermissionTargetTypeEnum;
import com.xspaceagi.system.spec.enums.YnEnum;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
public class SysDataPermissionDomainServiceImpl implements SysDataPermissionDomainService {

    @Resource
    private SysDataPermissionService sysDataPermissionService;

    @Override
    public void add(SysDataPermission dataPermission, UserContext userContext) {
        if (PermissionTargetTypeEnum.getByCode(dataPermission.getTargetType()) == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemDataPermissionTargetTypeInvalid);
        }
        if (dataPermission.getTargetId() == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemDataPermissionTargetIdInvalid);
        }
        dataPermission.setTenantId(userContext.getTenantId());
        dataPermission.setCreatorId(userContext.getUserId());
        dataPermission.setCreator(userContext.getUserName());
        dataPermission.setYn(YnEnum.Y.getKey());
        sysDataPermissionService.save(dataPermission);
    }

    @Override
    public void update(Long id, SysDataPermission dataPermission, UserContext userContext) {
        SysDataPermission updateObject = new SysDataPermission();
        updateObject.setId(id);
        updateObject.setModelIds(dataPermission.getModelIds());
        updateObject.setTokenLimit(dataPermission.getTokenLimit());
        updateObject.setMaxSpaceCount(dataPermission.getMaxSpaceCount());
        updateObject.setMaxAgentCount(dataPermission.getMaxAgentCount());
        updateObject.setMaxPageAppCount(dataPermission.getMaxPageAppCount());
        updateObject.setMaxKnowledgeCount(dataPermission.getMaxKnowledgeCount());
        updateObject.setKnowledgeStorageLimitGb(dataPermission.getKnowledgeStorageLimitGb());
        updateObject.setMaxDataTableCount(dataPermission.getMaxDataTableCount());
        updateObject.setMaxScheduledTaskCount(dataPermission.getMaxScheduledTaskCount());
        //updateObject.setAllowApiExternalCall(dataPermission.getAllowApiExternalCall());
        updateObject.setAgentComputerMemoryGb(dataPermission.getAgentComputerMemoryGb());
        updateObject.setAgentComputerSwapGb(dataPermission.getAgentComputerSwapGb());
        updateObject.setAgentComputerCpuCores(dataPermission.getAgentComputerCpuCores());
        updateObject.setAgentFileStorageDays(dataPermission.getAgentFileStorageDays());
        updateObject.setAgentDailyPromptLimit(dataPermission.getAgentDailyPromptLimit());
        updateObject.setPageDailyPromptLimit(dataPermission.getPageDailyPromptLimit());
        updateObject.setModifierId(userContext.getUserId());
        updateObject.setModifier(userContext.getUserName());
        sysDataPermissionService.updateById(updateObject);
    }

    @Override
    public SysDataPermission getByTarget(PermissionTargetTypeEnum targetType, Long targetId) {
        if (targetType == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemDataPermissionTargetTypeInvalid);
        }
        if (targetId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemDataPermissionTargetIdInvalid);
        }
        return sysDataPermissionService.getOne(Wrappers.<SysDataPermission>lambdaQuery()
                .eq(SysDataPermission::getTargetType, targetType.getCode())
                .eq(SysDataPermission::getTargetId, targetId)
                .eq(SysDataPermission::getYn, YnEnum.Y.getKey()));
    }

    @Override
    public List<SysDataPermission> getByTargetList(PermissionTargetTypeEnum targetType, List<Long> targetIds) {
        if (targetType == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemDataPermissionTargetTypeInvalid);
        }
        if (targetIds == null || targetIds.isEmpty()) {
            return Collections.emptyList();
        }
        return sysDataPermissionService.list(Wrappers.<SysDataPermission>lambdaQuery()
                .eq(SysDataPermission::getTargetType, targetType.getCode())
                .in(SysDataPermission::getTargetId, targetIds)
                .eq(SysDataPermission::getYn, YnEnum.Y.getKey()));
    }

    @Override
    public void deleteByTaret(PermissionTargetTypeEnum targetType, Long targetId, UserContext userContext) {
        LambdaUpdateWrapper<SysDataPermission> wrapper = Wrappers.<SysDataPermission>lambdaUpdate()
                .eq(SysDataPermission::getTargetType, targetType.getCode())
                .eq(SysDataPermission::getTargetId, targetId);
        sysDataPermissionService.remove(wrapper);
    }
}