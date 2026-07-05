package com.xspaceagi.agent.web.ui.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xspaceagi.agent.core.adapter.dto.recommend.RecommendHomeResponse;
import com.xspaceagi.agent.core.adapter.dto.recommend.TargetRecommendResponse;
import com.xspaceagi.agent.core.adapter.repository.entity.TargetRecommend;
import com.xspaceagi.agent.core.infra.dao.mapper.TargetRecommendMapper;
import com.xspaceagi.agent.web.ui.controller.base.BaseController;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Tag(name = "推荐接口")
@RestController
@RequestMapping("/api/display/recommend")
@Slf4j
public class RecommendController extends BaseController {

    @Resource
    private TargetRecommendMapper targetRecommendMapper;

    @Operation(summary = "获取首页和对话框推荐列表")
    @RequestMapping(path = "/list", method = RequestMethod.GET)
    public ReqResult<RecommendHomeResponse> list() {
        Long tenantId = RequestContext.get().getTenantId();

        List<TargetRecommend> homeList = targetRecommendMapper.selectList(
                new LambdaQueryWrapper<TargetRecommend>()
                        .eq(TargetRecommend::getTenantId, tenantId)
                        .eq(TargetRecommend::getRecType, TargetRecommend.RecType.Home)
                        .orderByAsc(TargetRecommend::getSort)
        );

        List<TargetRecommend> chatBoxNavList = targetRecommendMapper.selectList(
                new LambdaQueryWrapper<TargetRecommend>()
                        .eq(TargetRecommend::getTenantId, tenantId)
                        .eq(TargetRecommend::getRecType, TargetRecommend.RecType.ChatBoxNav)
                        .orderByAsc(TargetRecommend::getSort)
        );

        Map<String, List<TargetRecommendResponse>> recHome = homeList.stream()
                .map(this::toResponse)
                .collect(Collectors.groupingBy(
                        r -> r.getTargetType(),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        Map<String, List<TargetRecommendResponse>> recChatBoxNav = chatBoxNavList.stream()
                .map(this::toResponse)
                .collect(Collectors.groupingBy(
                        r -> r.getTargetType(),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        return ReqResult.success(RecommendHomeResponse.builder()
                .recHome(recHome)
                .recChatBoxNav(recChatBoxNav)
                .build());
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
