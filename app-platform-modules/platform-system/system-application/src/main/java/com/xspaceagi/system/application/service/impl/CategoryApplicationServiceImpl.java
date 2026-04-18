package com.xspaceagi.system.application.service.impl;

import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xspaceagi.system.infra.dao.entity.Category;
import com.xspaceagi.system.infra.dao.service.CategoryService;
import com.xspaceagi.system.application.service.CategoryApplicationService;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.CategoryTypeEnum;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 分类管理应用服务实现
 */
@Slf4j
@Service
public class CategoryApplicationServiceImpl implements CategoryApplicationService {

    @Resource
    private CategoryService categoryService;

    @Override
    public ReqResult<Category> create(Category category, UserContext userContext) {
        log.info("[create] Create category, name={}, code={}, type={}", category.getName(), category.getCode(), category.getType());

        // 校验类型
        if (CategoryTypeEnum.isInValid(category.getType())) {
            return ReqResult.error("0001", "分类类型无效");
        }

        // 校验编码是否已存在
        Category existing = categoryService.getOne(
                Wrappers.<Category>lambdaQuery()
                        .eq(Category::getCode, category.getCode())
                        .eq(Category::getTenantId, userContext.getTenantId())
        );
        if (existing != null) {
            log.error("[create] 分类编码已存在, code={}", category.getCode());
            return ReqResult.error("0002", "分类编码已存在");
        }

        category.setTenantId(userContext.getTenantId());
        category.setCreated(new Date());
        category.setModified(new Date());

        categoryService.save(category);

        log.info("[create] 创建分类成功, id={}", category.getId());
        return ReqResult.success(category);
    }

    @Override
    public ReqResult<Category> update(Category category, UserContext userContext) {
        log.info("[update] Update category, id={}, name={}, code={}, type={}",
                category.getId(), category.getName(), category.getCode(), category.getType());

        Category existing = categoryService.getById(category.getId());
        if (existing == null) {
            log.error("[update] 分类不存在, id={}", category.getId());
            return ReqResult.error("0003", "分类不存在");
        }

        // 校验租户
        if (!existing.getTenantId().equals(userContext.getTenantId())) {
            log.error("[update] 无权限操作该分类, id={}", category.getId());
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
        }

        // 校验类型
        if (category.getType() != null && CategoryTypeEnum.isInValid(category.getType())) {
            return ReqResult.error("0001", "分类类型无效");
        }

        // 校验编码是否已被其他记录使用
        if (category.getCode() != null) {
            Category duplicate = categoryService.getOne(
                    Wrappers.<Category>lambdaQuery()
                            .eq(Category::getCode, category.getCode())
                            .eq(Category::getTenantId, userContext.getTenantId())
                            .ne(Category::getId, category.getId())
            );
            if (duplicate != null) {
                log.error("[update] 分类编码已被其他记录使用, code={}", category.getCode());
                return ReqResult.error("0005", "分类编码已被其他记录使用");
            }
        }

        // 更新字段
        if (category.getName() != null) {
            existing.setName(category.getName());
        }
        if (category.getCode() != null) {
            existing.setCode(category.getCode());
        }
        if (category.getType() != null) {
            existing.setType(category.getType());
        }
        if (category.getDescription() != null) {
            existing.setDescription(category.getDescription());
        }
        existing.setModified(new Date());

        categoryService.updateById(existing);

        log.info("[update] 更新分类成功, id={}", existing.getId());
        return ReqResult.success(existing);
    }

    @Override
    public ReqResult<Void> delete(Long id, UserContext userContext) {
        log.info("[delete] Delete category, id={}", id);

        Category existing = categoryService.getById(id);
        if (existing == null) {
            log.error("[delete] 分类不存在, id={}", id);
            return ReqResult.error("0003", "分类不存在");
        }

        // 校验租户
        if (!existing.getTenantId().equals(userContext.getTenantId())) {
            log.error("[delete] 无权限操作该分类, id={}", id);
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
        }

        categoryService.removeById(id);

        log.info("[delete] 删除分类成功, id={}", id);
        return ReqResult.success(null);
    }

    @Override
    public Category getById(Long id) {
        return categoryService.getById(id);
    }

    @Override
    public List<Category> listByType(String type) {
        return categoryService.list(
                Wrappers.<Category>lambdaQuery()
                        .eq(Category::getType, type)
                        .orderByAsc(Category::getCreated)
        );
    }

    @Override
    public List<Category> listByTenantId(Long tenantId) {
        return categoryService.list(
                Wrappers.<Category>lambdaQuery()
                        .eq(Category::getTenantId, tenantId)
                        .orderByAsc(Category::getCreated)
        );
    }

    @Override
    public List<Category> listByTypeAndTenantId(String type, Long tenantId) {
        return categoryService.list(
                Wrappers.<Category>lambdaQuery()
                        .eq(Category::getType, type)
                        .eq(Category::getTenantId, tenantId)
                        .orderByAsc(Category::getCreated)
        );
    }

    @Override
    public Category getByCode(String code) {
        return categoryService.getOne(
                Wrappers.<Category>lambdaQuery()
                        .eq(Category::getCode, code)
                        .last("limit 1")
        );
    }
}
