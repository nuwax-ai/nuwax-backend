package com.xspaceagi.agent.core.infra.rpc;

import com.xspaceagi.system.sdk.service.CategoryApiService;
import com.xspaceagi.system.sdk.service.dto.CategoryDto;
import com.xspaceagi.system.sdk.service.dto.CategoryTypeEnum;
import jakarta.annotation.Resource;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CategoryRpcService {

    @Resource
    private CategoryApiService categoryApiService;


    public List<CategoryDto> listByTypeAndTenantId(String type, Long tenantId) {
        List<CategoryDto> categoryDtos = categoryApiService.listByTypeAndTenantId(type, tenantId);
        if (CollectionUtils.isEmpty(categoryDtos)) {
            List<com.xspaceagi.agent.core.adapter.dto.CategoryDto> categoryDtos1 = null;
            if (StringUtils.equals(type, CategoryTypeEnum.AGENT.getCode())) {
                categoryDtos1 = com.xspaceagi.agent.core.adapter.dto.CategoryDto.convertAgentCategoryList();
            } else if (StringUtils.equals(type, CategoryTypeEnum.PAGE_APP.getCode())) {
                categoryDtos1 = com.xspaceagi.agent.core.adapter.dto.CategoryDto.convertPageAppCategoryList();
            } else if (StringUtils.equals(type, CategoryTypeEnum.COMPONENT.getCode())) {
                categoryDtos1 = com.xspaceagi.agent.core.adapter.dto.CategoryDto.convertPluginCategoryList();
            } else if (StringUtils.equals(type, CategoryTypeEnum.SKILL.getCode())) {
                categoryDtos1 = com.xspaceagi.agent.core.adapter.dto.CategoryDto.convertSkillCategoryList();
            }
            if (categoryDtos1 != null) {
                categoryDtos1.forEach(categoryDto -> {
                    CategoryDto categoryDto1 = new CategoryDto();
                    categoryDto1.setCode(categoryDto.getName());
                    categoryDto1.setName(categoryDto.getDescription());
                    categoryDto1.setDescription(categoryDto.getDescription());
                    categoryDto1.setType(type);
                    categoryDto1.setTenantId(tenantId);
                    categoryApiService.insert(categoryDto1);
                });
            }
            categoryDtos = categoryApiService.listByTypeAndTenantId(type, tenantId);
        }
        return categoryDtos;
    }
}
