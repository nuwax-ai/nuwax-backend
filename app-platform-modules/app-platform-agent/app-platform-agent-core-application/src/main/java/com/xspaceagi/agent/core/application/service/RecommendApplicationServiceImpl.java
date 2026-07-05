package com.xspaceagi.agent.core.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xspaceagi.agent.core.adapter.application.RecommendApplicationService;
import com.xspaceagi.agent.core.adapter.dto.recommend.RecommendHomeResponse;
import com.xspaceagi.agent.core.adapter.dto.recommend.TargetRecommendPageRequest;
import com.xspaceagi.agent.core.adapter.dto.recommend.TargetRecommendResponse;
import com.xspaceagi.agent.core.adapter.dto.recommend.TargetRecommendSaveRequest;
import com.xspaceagi.agent.core.adapter.repository.entity.TargetRecommend;
import com.xspaceagi.agent.core.infra.dao.mapper.TargetRecommendMapper;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class RecommendApplicationServiceImpl implements RecommendApplicationService {

    @Resource
    private TargetRecommendMapper targetRecommendMapper;

    @Override
    public void save(TargetRecommendSaveRequest request) {
        Long tenantId = getTenantId();
        TargetRecommend entity = toEntity(request);
        entity.setTenantId(tenantId);
        if (entity.getSort() == null) {
            entity.setSort(0);
        }
        targetRecommendMapper.insert(entity);
    }

    @Override
    public void update(TargetRecommendSaveRequest request) {
        if (request.getId() == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemGetTenantFailed);
        }
        Long tenantId = getTenantId();
        TargetRecommend existing = targetRecommendMapper.selectById(request.getId());
        if (existing == null || !existing.getTenantId().equals(tenantId)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemGetTenantFailed);
        }
        TargetRecommend entity = toEntity(request);
        entity.setId(request.getId());
        entity.setTenantId(tenantId);
        targetRecommendMapper.updateById(entity);
    }

    @Override
    public void delete(Long id) {
        Long tenantId = getTenantId();
        TargetRecommend existing = targetRecommendMapper.selectById(id);
        if (existing == null || !existing.getTenantId().equals(tenantId)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemGetTenantFailed);
        }
        targetRecommendMapper.deleteById(id);
    }

    @Override
    public TargetRecommendResponse getById(Long id) {
        TargetRecommend entity = targetRecommendMapper.selectById(id);
        if (entity == null) {
            return null;
        }
        return toResponse(entity);
    }

    @Override
    public List<TargetRecommendResponse> page(TargetRecommendPageRequest request) {
        Long tenantId = getTenantId();
        Page<TargetRecommend> page = new Page<>(request.getPageNo(), request.getPageSize());
        LambdaQueryWrapper<TargetRecommend> wrapper = new LambdaQueryWrapper<TargetRecommend>()
                .eq(TargetRecommend::getTenantId, tenantId)
                .eq(StringUtils.isNotBlank(request.getRecType()),
                        TargetRecommend::getRecType,
                        request.getRecType() != null ? TargetRecommend.RecType.valueOf(request.getRecType()) : null)
                .eq(StringUtils.isNotBlank(request.getTargetType()),
                        TargetRecommend::getTargetType,
                        request.getTargetType() != null ? TargetRecommend.TargetType.valueOf(request.getTargetType()) : null)
                .orderByAsc(TargetRecommend::getSort)
                .orderByDesc(TargetRecommend::getCreated);
        IPage<TargetRecommend> result = targetRecommendMapper.selectPage(page, wrapper);
        return result.getRecords().stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Override
    public List<TargetRecommendResponse> list(String recType, String targetType) {
        return targetRecommendMapper.selectList(
                new LambdaQueryWrapper<TargetRecommend>()
                        .eq(TargetRecommend::getTenantId, getTenantId())
                        .eq(StringUtils.isNotBlank(recType), TargetRecommend::getRecType, recType != null ? TargetRecommend.RecType.valueOf(recType) : null)
                        .eq(StringUtils.isNotBlank(targetType), TargetRecommend::getTargetType, targetType != null ? TargetRecommend.TargetType.valueOf(targetType) : null)
                        .orderByAsc(TargetRecommend::getSort)
                        .orderByDesc(TargetRecommend::getCreated)
        ).stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Override
    public void updateSort(Long id, Integer sort) {
        LambdaUpdateWrapper<TargetRecommend> wrapper = new LambdaUpdateWrapper<TargetRecommend>()
                .eq(TargetRecommend::getId, id)
                .set(TargetRecommend::getSort, sort);
        targetRecommendMapper.update(null, wrapper);
    }

    @Override
    public RecommendHomeResponse getRecommendations() {
        Long tenantId = getTenantId();
        List<TargetRecommend> all = targetRecommendMapper.selectList(
                new LambdaQueryWrapper<TargetRecommend>()
                        .eq(TargetRecommend::getTenantId, tenantId)
                        .eq(TargetRecommend::getRecType, TargetRecommend.RecType.Home)
                        .orderByAsc(TargetRecommend::getSort)
        );
        List<TargetRecommend> chatBoxNavAll = targetRecommendMapper.selectList(
                new LambdaQueryWrapper<TargetRecommend>()
                        .eq(TargetRecommend::getTenantId, tenantId)
                        .eq(TargetRecommend::getRecType, TargetRecommend.RecType.ChatBoxNav)
                        .orderByAsc(TargetRecommend::getSort)
        );

        Map<String, List<TargetRecommendResponse>> recHome = all.stream()
                .map(this::toResponse)
                .collect(Collectors.groupingBy(
                        r -> r.getTargetType(),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        Map<String, List<TargetRecommendResponse>> recChatBoxNav = chatBoxNavAll.stream()
                .map(this::toResponse)
                .collect(Collectors.groupingBy(
                        r -> r.getTargetType(),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        return RecommendHomeResponse.builder()
                .recHome(recHome)
                .recChatBoxNav(recChatBoxNav)
                .build();
    }

    private Long getTenantId() {
        Long tenantId = RequestContext.get() != null ? RequestContext.get().getTenantId() : null;
        if (tenantId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemGetTenantFailed);
        }
        return tenantId;
    }

    private TargetRecommend toEntity(TargetRecommendSaveRequest request) {
        TargetRecommend entity = new TargetRecommend();
        entity.setTargetType(TargetRecommend.TargetType.valueOf(request.getTargetType()));
        entity.setTargetId(request.getTargetId());
        entity.setRecType(TargetRecommend.RecType.valueOf(request.getRecType()));
        entity.setFunctionType(TargetRecommend.FunctionType.valueOf(request.getFunctionType()));
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
