package com.xspaceagi.agent.web.ui.controller.manage;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xspaceagi.agent.core.adapter.dto.recommend.TargetRecommendResponse;
import com.xspaceagi.agent.core.adapter.dto.recommend.TargetRecommendSaveRequest;
import com.xspaceagi.agent.core.adapter.repository.TargetRecommendRepository;
import com.xspaceagi.agent.core.adapter.repository.entity.TargetRecommend;
import com.xspaceagi.agent.core.infra.dao.mapper.TargetRecommendMapper;
import com.xspaceagi.agent.web.ui.controller.manage.dto.ManagePageResponse;
import com.xspaceagi.agent.web.ui.controller.manage.dto.ManageQueryRequest;
import com.xspaceagi.system.application.dto.permission.SortIndexUpdateDto;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

import static com.xspaceagi.system.spec.enums.ResourceEnum.*;

@Tag(name = "展示推荐管理")
@RestController
@RequestMapping("/api/system/display/recommend")
public class RecommendManageController extends BaseManageController {

    @Resource
    private TargetRecommendMapper targetRecommendMapper;

    @Resource
    private TargetRecommendRepository targetRecommendRepository;

    @RequireResource(DISPLAY_RECOMMEND_QUERY)
    @Operation(summary = "查询推荐列表")
    @PostMapping("/list")
    public ReqResult<ManagePageResponse<TargetRecommendResponse>> list(@RequestBody ManageQueryRequest request) {
        Page<TargetRecommend> page = new Page<>(request.getPageNo(), request.getPageSize());
        Long tenantId = RequestContext.get().getTenantId();
        TargetRecommend.RecType recType = null;
        if (StringUtils.isNotBlank(request.getRecType())) {
            try {
                recType = TargetRecommend.RecType.valueOf(request.getRecType());
            } catch (IllegalArgumentException e) {
                return ReqResult.error("recType参数无效");
            }
        }
        TargetRecommend.TargetType targetType = null;
        if (StringUtils.isNotBlank(request.getTargetType())) {
            try {
                targetType = TargetRecommend.TargetType.valueOf(request.getTargetType());
            } catch (IllegalArgumentException e) {
                return ReqResult.error("targetType参数无效");
            }
        }
        TargetRecommend.RecType filterRecType = recType;
        TargetRecommend.TargetType filterTargetType = targetType;
        LambdaQueryWrapper<TargetRecommend> wrapper = new LambdaQueryWrapper<TargetRecommend>()
                .eq(TargetRecommend::getTenantId, tenantId)
                .like(request.getName() != null, TargetRecommend::getLabel, request.getName())
                .eq(filterRecType != null, TargetRecommend::getRecType, filterRecType)
                .eq(filterTargetType != null, TargetRecommend::getTargetType, filterTargetType)
                .orderByAsc(TargetRecommend::getSort)
                .orderByDesc(TargetRecommend::getCreated);

        IPage<TargetRecommend> result = targetRecommendMapper.selectPage(page, wrapper);

        List<TargetRecommendResponse> items = result.getRecords().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());

        ManagePageResponse<TargetRecommendResponse> response = ManagePageResponse.<TargetRecommendResponse>builder()
                .total(result.getTotal())
                .pageNo(request.getPageNo())
                .pageSize(request.getPageSize())
                .records(items)
                .build();

        return ReqResult.success(response);
    }

    @RequireResource(DISPLAY_RECOMMEND_SAVE)
    @Operation(summary = "新增推荐")
    @PostMapping("/save")
    public ReqResult<Void> save(@RequestBody TargetRecommendSaveRequest request) {
        String validateError = validateSaveRequest(request);
        if (validateError != null) {
            return ReqResult.error(validateError);
        }
        Long tenantId = RequestContext.get().getTenantId();
        String duplicateError = validateDuplicate(request, tenantId);
        if (duplicateError != null) {
            return ReqResult.error(duplicateError);
        }
        TargetRecommend entity = toEntity(request);
        entity.setTenantId(tenantId);
        if (entity.getSort() == null) {
            entity.setSort(0);
        }
        targetRecommendMapper.insert(entity);
        return ReqResult.success(null);
    }

    @RequireResource(DISPLAY_RECOMMEND_SAVE)
    @Operation(summary = "编辑推荐")
    @PostMapping("/update")
    public ReqResult<Void> update(@RequestBody TargetRecommendSaveRequest request) {
        String validateError = validateSaveRequest(request);
        if (validateError != null) {
            return ReqResult.error(validateError);
        }
        Long tenantId = RequestContext.get().getTenantId();
        String duplicateError = validateDuplicate(request, tenantId);
        if (duplicateError != null) {
            return ReqResult.error(duplicateError);
        }
        TargetRecommend entity = toEntity(request);
        entity.setId(request.getId());
        entity.setTenantId(tenantId);
        targetRecommendMapper.updateById(entity);
        return ReqResult.success(null);
    }

    @RequireResource(DISPLAY_RECOMMEND_DELETE)
    @Operation(summary = "删除推荐")
    @PostMapping("/delete/{id}")
    public ReqResult<Void> delete(@PathVariable Long id) {
        targetRecommendMapper.deleteById(id);
        return ReqResult.success(null);
    }

    @RequireResource(DISPLAY_RECOMMEND_SAVE)
    @Operation(summary = "批量调整排序")
    @PostMapping("/updateSort")
    public ReqResult<Void> updateSort(@RequestBody SortIndexUpdateDto dto) {
        if (dto == null || CollectionUtils.isEmpty(dto.getItems())) {
            return ReqResult.error("参数不能为空");
        }
        List<TargetRecommend> updateList = dto.getItems().stream()
                .map(item -> {
                    TargetRecommend entity = new TargetRecommend();
                    entity.setId(item.getId());
                    entity.setSort(item.getSortIndex());
                    return entity;
                })
                .collect(Collectors.toList());
        targetRecommendRepository.updateBatchById(updateList);
        return ReqResult.success(null);
    }

    private String validateSaveRequest(TargetRecommendSaveRequest request) {
        if (request == null) {
            return "参数不能为空";
        }
        if (StringUtils.isBlank(request.getRecType())) {
            return "recType不能为空";
        }
        if (StringUtils.isBlank(request.getTargetType())) {
            return "targetType不能为空";
        }
        if (request.getTargetId() == null) {
            return "targetId不能为空";
        }
        try {
            TargetRecommend.RecType.valueOf(request.getRecType());
            TargetRecommend.TargetType.valueOf(request.getTargetType());
        } catch (IllegalArgumentException e) {
            return "recType或targetType参数无效";
        }
        return null;
    }

    private String validateDuplicate(TargetRecommendSaveRequest request, Long tenantId) {
        if (StringUtils.isBlank(request.getRecType())) {
            return null;
        }
        TargetRecommend.RecType recType = TargetRecommend.RecType.valueOf(request.getRecType());
        LambdaQueryWrapper<TargetRecommend> wrapper = new LambdaQueryWrapper<TargetRecommend>()
                .eq(TargetRecommend::getTenantId, tenantId)
                .eq(TargetRecommend::getRecType, recType);
        if (request.getId() != null) {
            wrapper.ne(TargetRecommend::getId, request.getId());
        }

        if (recType == TargetRecommend.RecType.Home || recType == TargetRecommend.RecType.Official) {
            wrapper.eq(TargetRecommend::getTargetType, TargetRecommend.TargetType.valueOf(request.getTargetType()))
                    .eq(TargetRecommend::getTargetId, request.getTargetId());
        } else if (recType == TargetRecommend.RecType.ChatBoxNav) {
            if (StringUtils.isBlank(request.getFunctionType())) {
                return null;
            }
            TargetRecommend.FunctionType functionType = TargetRecommend.FunctionType.valueOf(request.getFunctionType());
            wrapper.eq(TargetRecommend::getFunctionType, functionType)
                    .eq(TargetRecommend::getTargetType, TargetRecommend.TargetType.valueOf(request.getTargetType()));
            if (functionType == TargetRecommend.FunctionType.Chat) {
                wrapper.eq(TargetRecommend::getTargetId, request.getTargetId());
            }
        } else {
            return null;
        }

        Long count = targetRecommendMapper.selectCount(wrapper);
        if (count != null && count > 0) {
            if (recType == TargetRecommend.RecType.ChatBoxNav) {
                TargetRecommend.FunctionType functionType = TargetRecommend.FunctionType.valueOf(request.getFunctionType());
                if (functionType == TargetRecommend.FunctionType.Chat) {
                    return "相同功能类型、推荐类型、目标类型和目标已存在";
                }
                return "相同功能类型、推荐类型和目标类型的配置已存在";
            }
            return "相同推荐类型、目标类型和目标已存在";
        }
        return null;
    }

    private TargetRecommend toEntity(TargetRecommendSaveRequest request) {
        TargetRecommend entity = new TargetRecommend();
        if (StringUtils.isNotBlank(request.getTargetType())) {
            entity.setTargetType(TargetRecommend.TargetType.valueOf(request.getTargetType()));
        }
        entity.setTargetId(request.getTargetId());
        if (StringUtils.isNotBlank(request.getRecType())) {
            entity.setRecType(TargetRecommend.RecType.valueOf(request.getRecType()));
        }
        if (StringUtils.isNotBlank(request.getFunctionType())) {
            entity.setFunctionType(TargetRecommend.FunctionType.valueOf(request.getFunctionType()));
        }
        entity.setLabel(request.getLabel());
        entity.setIcon(request.getIcon());
        entity.setPlaceholder(request.getPlaceholder());
        entity.setSort(request.getSort());
        return entity;
    }

    private TargetRecommendResponse toResponse(TargetRecommend entity) {
        return TargetRecommendResponse.builder()
                .id(entity.getId())
                .tenantId(entity.getTenantId())
                .targetType(entity.getTargetType() != null ? entity.getTargetType().name() : null)
                .targetId(entity.getTargetId())
                .recType(entity.getRecType() != null ? entity.getRecType().name() : null)
                .functionType(entity.getFunctionType() != null ? entity.getFunctionType().name() : null)
                .label(entity.getLabel())
                .icon(entity.getIcon())
                .placeholder(entity.getPlaceholder())
                .sort(entity.getSort())
                .modified(entity.getModified())
                .created(entity.getCreated())
                .build();
    }
}
