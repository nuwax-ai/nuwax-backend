package com.xspaceagi.system.web.controller;

import com.xspaceagi.system.application.dto.CategoryCreateDto;
import com.xspaceagi.system.application.dto.CategoryUpdateDto;
import com.xspaceagi.system.application.service.CategoryApplicationService;
import com.xspaceagi.system.infra.dao.entity.Category;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.utils.I18nUtil;
import com.xspaceagi.system.web.controller.base.BaseController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.xspaceagi.system.spec.enums.ResourceEnum.*;

/**
 * 分类管理控制器
 */
@Slf4j
@Tag(name = "分类管理", description = "分类管理相关接口")
@RestController
@RequestMapping("/api/system/category")
public class CategoryController extends BaseController {

    @Resource
    private CategoryApplicationService categoryApplicationService;

    /**
     * 创建分类
     */
    @RequireResource(CATEGORY_CONFIG_ADD)
    @Operation(summary = "创建分类")
    @PostMapping(value = "/create", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Category> create(@RequestBody CategoryCreateDto dto) {
        log.info("[create] Create category, name={}, code={}, type={}", dto.getName(), dto.getCode(), dto.getType());
        Assert.notNull(dto.getName(), "Name is required");
        Assert.notNull(dto.getCode(), "Code is required");
        Assert.notNull(dto.getType(), "Type is required");
        Category category = new Category();
        category.setName(dto.getName());
        category.setCode(dto.getCode());
        category.setType(dto.getType());
        category.setDescription(dto.getDescription());

        return categoryApplicationService.create(category, getUser());
    }

    /**
     * 更新分类
     */
    @RequireResource(CATEGORY_CONFIG_MODIFY)
    @Operation(summary = "更新分类")
    @PostMapping(value = "/update", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Category> update(@RequestBody CategoryUpdateDto dto) {
        log.info("[update] Update category, id={}, name={}, code={}, type={}",
                dto.getId(), dto.getName(), dto.getCode(), dto.getType());
        Assert.notNull(dto.getId(), "ID is required");
        Category category = new Category();
        category.setId(dto.getId());
        category.setName(dto.getName());
        category.setCode(dto.getCode());
        category.setType(dto.getType());
        category.setDescription(dto.getDescription());

        return categoryApplicationService.update(category, getUser());
    }

    /**
     * 删除分类
     */
    @RequireResource(CATEGORY_CONFIG_DELETE)
    @Operation(summary = "删除分类")
    @PostMapping(value = "/delete/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Void> delete(@PathVariable("id") Long id) {
        log.info("[delete] Delete category, id={}", id);
        Assert.notNull(id, "ID不能为空");
        return categoryApplicationService.delete(id, getUser());
    }

    /**
     * 根据类型查询分类列表
     */
    @RequireResource(CATEGORY_CONFIG_QUERY)
    @Operation(summary = "根据类型查询分类列表")
    @GetMapping(value = "/list", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<List<Category>> listByType(@RequestParam("type") String type) {
        log.info("[listByType] 查询分类列表, type={}", type);
        List<Category> list = categoryApplicationService.listByType(type);

        I18nUtil.replaceSystemMessage(list);
        return ReqResult.success(list);
    }

}
