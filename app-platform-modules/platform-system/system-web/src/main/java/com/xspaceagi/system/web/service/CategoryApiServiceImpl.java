package com.xspaceagi.system.web.service;

import com.xspaceagi.system.application.service.CategoryApplicationService;
import com.xspaceagi.system.infra.dao.entity.Category;
import com.xspaceagi.system.infra.dao.service.CategoryService;
import com.xspaceagi.system.sdk.service.CategoryApiService;
import com.xspaceagi.system.sdk.service.dto.CategoryDto;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import jakarta.annotation.Resource;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 分类API服务实现
 */
@Service
public class CategoryApiServiceImpl implements CategoryApiService {

    @Resource
    private CategoryApplicationService categoryApplicationService;

    @Resource
    private CategoryService categoryService;

    @Override
    public CategoryDto insert(CategoryDto categoryDto) {
        Assert.notNull(categoryDto, "categoryDto不能为空");
        Assert.hasText(categoryDto.getName(), "名称不能为空");
        Assert.hasText(categoryDto.getCode(), "编码不能为空");
        Assert.hasText(categoryDto.getType(), "类型不能为空");
        Assert.notNull(categoryDto.getTenantId(), "租户ID不能为空");
        Category category = new Category();
        BeanUtils.copyProperties(categoryDto, category);
        TenantFunctions.runWithIgnoreCheck(() -> categoryService.save(category));
        return convertToDto(category);
    }

    @Override
    public CategoryDto getById(Long id) {
        Category category = categoryApplicationService.getById(id);
        return convertToDto(category);
    }

    @Override
    public List<CategoryDto> listByType(String type) {
        List<Category> categories = categoryApplicationService.listByType(type);
        return categories.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    @Override
    public List<CategoryDto> listByTenantId(Long tenantId) {
        List<Category> categories = categoryApplicationService.listByTenantId(tenantId);
        return categories.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    @Override
    public List<CategoryDto> listByTypeAndTenantId(String type, Long tenantId) {
        Assert.hasText(type, "type cannot be null");
        Assert.notNull(tenantId, "tenantId cannot be null");
        List<Category> categories = TenantFunctions.callWithIgnoreCheck(() -> categoryApplicationService.listByTypeAndTenantId(type, tenantId));
        return categories.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    @Override
    public CategoryDto getByCode(String code) {
        Category category = categoryApplicationService.getByCode(code);
        return convertToDto(category);
    }

    /**
     * 将Category实体转换为CategoryDto
     */
    private CategoryDto convertToDto(Category category) {
        if (category == null) {
            return null;
        }
        CategoryDto dto = new CategoryDto();
        BeanUtils.copyProperties(category, dto);
        return dto;
    }
}