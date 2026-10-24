package com.xspaceagi.agent.web.ui.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xspaceagi.agent.core.adapter.dto.recommend.RecommendHomeResponse;
import com.xspaceagi.agent.core.adapter.dto.recommend.TargetRecommendResponse;
import com.xspaceagi.agent.core.adapter.repository.entity.AgentConfig;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.adapter.repository.entity.TargetRecommend;
import com.xspaceagi.agent.core.domain.service.AgentDomainService;
import com.xspaceagi.agent.core.infra.dao.mapper.TargetRecommendMapper;
import com.xspaceagi.agent.web.ui.controller.base.BaseController;
import com.xspaceagi.system.application.dto.SpaceDto;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.application.util.DefaultIconUrlUtil;
import com.xspaceagi.system.infra.dao.entity.Space;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
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

    @Resource
    private SpaceApplicationService spaceApplicationService;

    @Resource
    private AgentDomainService agentDomainService;


    @Operation(summary = "获取首页和对话框推荐列表")
    @RequestMapping(path = "/list", method = RequestMethod.GET)
    public ReqResult<RecommendHomeResponse> list(@RequestParam(required = false) boolean forApp) {
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

        if (forApp) {
            chatBoxNavList = chatBoxNavList.stream().filter(targetRecommend -> targetRecommend.getFunctionType() == TargetRecommend.FunctionType.Chat).collect(Collectors.toList());
            if (RequestContext.get().getUserId() != null) {
                SpaceDto spaceDto = spaceApplicationService.queryListByUserId(RequestContext.get().getUserId()).stream().filter(s -> s.getType() == Space.Type.Personal).findFirst().orElse(null);
                if (spaceDto != null) {
                    List<AgentConfig> agentConfigs = agentDomainService.queryListBySpaceId(spaceDto.getId());
                    for (AgentConfig agentConfig : agentConfigs) {
                        if (agentConfig.getExtra() != null && JSON.isValid(agentConfig.getExtra()) && agentConfig.getPublishStatus() == Published.PublishStatus.Published) {
                            JSONObject extra = JSON.parseObject(agentConfig.getExtra());
                            if (extra.containsKey("isDeviceAgent")) {
                                TargetRecommend targetRecommend = TargetRecommend.builder()
                                        .id(agentConfig.getId())
                                        .tenantId(agentConfig.getTenantId())
                                        .targetType(TargetRecommend.TargetType.Agent)
                                        .targetId(agentConfig.getId())
                                        .recType(TargetRecommend.RecType.ChatBoxNav)
                                        .functionType(TargetRecommend.FunctionType.Chat)
                                        .label(agentConfig.getName())
                                        .icon(DefaultIconUrlUtil.setDefaultIconUrl(agentConfig.getIcon(), agentConfig.getName(), "agent", true))
                                        .build();
                                chatBoxNavList.add(targetRecommend);
                            }
                        }
                    }
                }
            }
        }

        Map<String, List<TargetRecommendResponse>> recHome = homeList.stream()
                .map(this::toResponse)
                .collect(Collectors.groupingBy(
                        TargetRecommendResponse::getTargetType,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        Map<String, List<TargetRecommendResponse>> recChatBoxNav = chatBoxNavList.stream()
                .map(this::toResponse)
                .collect(Collectors.groupingBy(
                        TargetRecommendResponse::getTargetType,
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
                .category(entity.getCategory())
                .prompts(entity.getPrompts())
                .sort(entity.getSort())
                .modified(entity.getModified())
                .created(entity.getCreated())
                .build();
    }
}
