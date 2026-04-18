package com.xspaceagi.custompage.domain.service.impl;

import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import com.xspaceagi.custompage.domain.model.CustomPageDomainModel;
import com.xspaceagi.custompage.domain.repository.ICustomPageDomainRepository;
import com.xspaceagi.custompage.domain.service.ICustomPageDomainDomainService;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class CustomPageDomainDomainServiceImpl implements ICustomPageDomainDomainService {

    @Resource
    private ICustomPageDomainRepository customPageDomainRepository;

    @Override
    public List<CustomPageDomainModel> listByProjectId(Long projectId) {
        return customPageDomainRepository.listByProjectId(projectId);
    }

    @Override
    public CustomPageDomainModel getById(Long id) {
        return customPageDomainRepository.getById(id);
    }

    @Override
    public ReqResult<CustomPageDomainModel> create(CustomPageDomainModel model, UserContext userContext) {
        log.info("[create] Create domain binding, project Id={}, domain={}", model.getProjectId(), model.getDomain());

        // 校验域名是否已存在
        CustomPageDomainModel existing = customPageDomainRepository.getByDomain(model.getDomain());
        if (existing != null) {
            log.error("[create] domainalready exists, domain={}", model.getDomain());
            return ReqResult.error("0001", "Domain already exists");
        }

        model.setTenantId(userContext.getTenantId());
        model.setCreated(new Date());
        model.setModified(new Date());

        CustomPageDomainModel result = customPageDomainRepository.add(model);

        log.info("[create] createdomain bindingsucceeded, id={}", result.getId());
        return ReqResult.success(result);
    }

    @Override
    public ReqResult<CustomPageDomainModel> update(CustomPageDomainModel model, UserContext userContext) {
        log.info("[update] updatedomain binding, id={}, domain={}", model.getId(), model.getDomain());

        CustomPageDomainModel existing = customPageDomainRepository.getById(model.getId());
        if (existing == null) {
            log.error("[update] domain bindingnot found, id={}", model.getId());
            return ReqResult.error("0002", "Domain binding does not exist");
        }

        // 校验租户
        if (!existing.getTenantId().equals(userContext.getTenantId())) {
            log.error("[update] no permission to operate this domain binding, id={}", model.getId());
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
        }

        // 校验域名是否已被其他记录使用
        CustomPageDomainModel domainDuplicate = customPageDomainRepository.getByDomain(model.getDomain());
        if (domainDuplicate != null && !domainDuplicate.getId().equals(model.getId())) {
            log.error("[update] domain already used by another record, domain={}", model.getDomain());
            return ReqResult.error("0004", "Domain is already used by another binding");
        }

        existing.setDomain(model.getDomain());
        existing.setProjectId(model.getProjectId());
        existing.setModified(new Date());

        customPageDomainRepository.updateById(existing);

        log.info("[update] updatedomain bindingsucceeded, id={}", existing.getId());
        return ReqResult.success(existing);
    }

    @Override
    public ReqResult<Void> delete(Long id, UserContext userContext) {
        log.info("[delete] Delete domain binding, id={}", id);

        CustomPageDomainModel existing = customPageDomainRepository.getById(id);
        if (existing == null) {
            log.error("[delete] domain bindingnot found, id={}", id);
            return ReqResult.error("0005", "Domain binding does not exist");
        }

        // 校验租户
        if (!existing.getTenantId().equals(userContext.getTenantId())) {
            log.error("[delete] no permission to operate this domain binding, id={}", id);
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
        }

        customPageDomainRepository.removeById(id);

        log.info("[delete] deletedomain bindingsucceeded, id={}", id);
        return ReqResult.success(null);
    }

    @Override
    public List<String> listAllDomains() {
        return customPageDomainRepository.listAllDomains();
    }
}
