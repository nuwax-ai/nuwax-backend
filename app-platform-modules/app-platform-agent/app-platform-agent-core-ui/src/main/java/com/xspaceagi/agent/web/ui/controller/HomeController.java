package com.xspaceagi.agent.web.ui.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xspaceagi.agent.core.adapter.application.AgentApplicationService;
import com.xspaceagi.agent.core.adapter.application.PublishApplicationService;
import com.xspaceagi.agent.core.adapter.application.RecommendApplicationService;
import com.xspaceagi.agent.core.adapter.dto.PublishedDto;
import com.xspaceagi.agent.core.adapter.dto.PublishedQueryDto;
import com.xspaceagi.agent.core.adapter.dto.UserAgentDto;
import com.xspaceagi.agent.core.adapter.dto.recommend.TargetRecommendResponse;
import com.xspaceagi.agent.core.adapter.repository.UserAgentSortRepository;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.adapter.repository.entity.TargetRecommend;
import com.xspaceagi.agent.core.adapter.repository.entity.UserAgentSort;
import com.xspaceagi.agent.web.ui.controller.base.BaseController;
import com.xspaceagi.agent.web.ui.controller.dto.HomeSortConfigDto;
import com.xspaceagi.agent.web.ui.dto.HomeCategoryDto;
import com.xspaceagi.agent.web.ui.dto.HomeItemDto;
import com.xspaceagi.agent.web.ui.dto.HomeItemListDto;
import com.xspaceagi.system.application.dto.SpaceDto;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.application.util.DefaultIconUrlUtil;
import com.xspaceagi.system.infra.dao.entity.Space;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.page.SuperPage;
import com.xspaceagi.system.spec.utils.I18nUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Tag(name = "主页智能体列表接口")
@RestController
@RequestMapping("/api/home")
@Slf4j
public class HomeController extends BaseController {

    @Resource
    private PublishApplicationService publishApplicationService;

    @Resource
    private AgentApplicationService agentApplicationService;

    @Resource
    private SpaceApplicationService spaceApplicationService;

    @Resource
    private UserAgentSortRepository userAgentSortRepository;

    @Resource
    private RecommendApplicationService recommendApplicationService;

    @Operation(summary = "分类以及智能体排序更新")
    @RequestMapping(path = "/sort/update", method = RequestMethod.POST)
    public ReqResult<HomeItemListDto> updateHomeSortConfig(@RequestBody HomeSortConfigDto homeSortConfigDto) {
        userAgentSortRepository.updateSort(RequestContext.get().getUserId(), homeSortConfigDto.getTypes(), homeSortConfigDto.getTypeAgentIds());
        return ReqResult.success();
    }

    @Operation(summary = "数据列表查询")
    @RequestMapping(path = "/list", method = RequestMethod.GET)
    public ReqResult<HomeItemListDto> list() {
        //排序配置
        LambdaQueryWrapper<UserAgentSort> sortQueryWrapper = new LambdaQueryWrapper<>();
        sortQueryWrapper.eq(UserAgentSort::getUserId, RequestContext.get().getUserId());
        sortQueryWrapper.orderByAsc(UserAgentSort::getSort);
        List<UserAgentSort> userAgentSortList = userAgentSortRepository.list(sortQueryWrapper);
        //userAgentSortList以category为key转map
        Map<String, UserAgentSort> userAgentCategorySortMap = userAgentSortList.stream().collect(Collectors.toMap(UserAgentSort::getCategory, config -> config, (c1, c2) -> c1));
        Map<String, Map<Long, Integer>> userAgentSortMap = new HashMap<>();
        userAgentSortList.forEach(userAgentSort -> {
            if (CollectionUtils.isNotEmpty(userAgentSort.getAgentSortConfig())) {
                Map<Long, Integer> agentSortConfigMap = new HashMap<>();
                userAgentSortMap.put(userAgentSort.getCategory(), agentSortConfigMap);
                for (int i = 0; i < userAgentSort.getAgentSortConfig().size(); i++) {
                    agentSortConfigMap.put(userAgentSort.getAgentSortConfig().get(i), i);
                }
            }
        });

        List<UserAgentDto> usedAgentDtos = agentApplicationService.queryRecentUseList(RequestContext.get().getUserId(), 100);
        Map<Long, UserAgentDto> usedAgentMap = usedAgentDtos.stream().collect(Collectors.toMap(UserAgentDto::getAgentId, config -> config, (c1, c2) -> c1));
        HomeItemListDto homeItemListDto = new HomeItemListDto();
        List<UserAgentDto> userAgentDtos = agentApplicationService.queryCollectionList(RequestContext.get().getUserId(), 1, 30);
        if (!userAgentDtos.isEmpty()) {
            List<HomeItemDto> homeItemDtos = userAgentDtos.stream().map(userAgentDto -> {
                HomeItemDto homeItemDto = new HomeItemDto();
                homeItemDto.setTargetType(Published.TargetType.Agent);
                homeItemDto.setTargetId(userAgentDto.getAgentId());
                homeItemDto.setName(userAgentDto.getName());
                homeItemDto.setIcon(userAgentDto.getIcon());
                homeItemDto.setDescription(userAgentDto.getDescription());
                homeItemDto.setPublishUser(userAgentDto.getPublishUser());
                homeItemDto.setStatistics(userAgentDto.getStatistics());
                homeItemDto.setAgentType(userAgentDto.getAgentType());
                homeItemDto.setCollect(true);
                //获取智能体排序
                Integer sort = getAgentSort(userAgentSortMap.get(HomeCategoryDto.HomeCategoryTypeEnum.AGENT_COLLECT.name()), userAgentDto.getAgentId());
                homeItemDto.setSort(sort);
                UserAgentDto userUsedAgentDto = usedAgentMap.get(userAgentDto.getAgentId());
                if (userUsedAgentDto != null && userUsedAgentDto.getLastConversationId() != null) {
                    homeItemDto.setLastConversationId(userUsedAgentDto.getLastConversationId());
                }
                return homeItemDto;
            }).collect(Collectors.toList());
            //userAgentDtos根据sort从小到大排序
            homeItemDtos.sort(Comparator.comparing(HomeItemDto::getSort));
            Integer sort = getCategorySort(userAgentCategorySortMap, HomeCategoryDto.HomeCategoryTypeEnum.AGENT_COLLECT.name());
            homeItemListDto.getCategories().add(HomeCategoryDto.builder().type(HomeCategoryDto.HomeCategoryTypeEnum.AGENT_COLLECT.name()).categoryType(HomeCategoryDto.HomeCategoryTypeEnum.AGENT_COLLECT).name(I18nUtil.systemMessage("Backend.Home.Category.AgentCollect")).sort(sort).build());
            homeItemListDto.getCategoryItems().put(HomeCategoryDto.HomeCategoryTypeEnum.AGENT_COLLECT.name(), homeItemDtos);
        }

        TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
        List<Long> recommendAgentIds = recommendApplicationService.list(TargetRecommend.RecType.Official.name(), TargetRecommend.TargetType.Agent.name()).stream().map(TargetRecommendResponse::getTargetId).collect(Collectors.toList());
        recommendAgentIds = recommendAgentIds.isEmpty() ? tenantConfigDto.getRecommendAgentIds() : recommendAgentIds;
        if (CollectionUtils.isNotEmpty(recommendAgentIds)) {
            List<PublishedDto> publishedDtos = publishApplicationService.queryPublishedList(Published.TargetType.Agent, recommendAgentIds);
            //根据recommendAgentIds的顺序对publishedDtos进行排序
            final List<Long> recommendAgentIds0 = recommendAgentIds;
            publishedDtos = publishedDtos.stream().sorted(Comparator.comparing(publishedDto -> recommendAgentIds0.indexOf(publishedDto.getTargetId()))).toList();
            List<HomeItemDto> homeItemDtos = publishedDtos.stream().map(publishedDto -> convertToHomeItemDto(publishedDto, userAgentSortMap.get(HomeCategoryDto.HomeCategoryTypeEnum.AGENT_RECOMMEND.name()), usedAgentMap)).collect(Collectors.toList());
            homeItemDtos.sort(Comparator.comparing(HomeItemDto::getSort));
            Integer sort = getCategorySort(userAgentCategorySortMap, HomeCategoryDto.HomeCategoryTypeEnum.AGENT_RECOMMEND.name());
            homeItemListDto.getCategories().add(HomeCategoryDto.builder().type(HomeCategoryDto.HomeCategoryTypeEnum.AGENT_RECOMMEND.name()).categoryType(HomeCategoryDto.HomeCategoryTypeEnum.AGENT_RECOMMEND).name(I18nUtil.systemMessage("Backend.Home.Category.AgentRecommend")).sort(sort).build());
            homeItemListDto.getCategoryItems().put(HomeCategoryDto.HomeCategoryTypeEnum.AGENT_RECOMMEND.name(), homeItemDtos);
        }
        //查询用户空间列表
        List<SpaceDto> spaceDtos = spaceApplicationService.queryListByUserId(RequestContext.get().getUserId());
        I18nUtil.replaceSystemMessage(spaceDtos);
        //获取spaceDtos的id列表
        List<Long> spaceIds = spaceDtos.stream().map(SpaceDto::getId).toList();
        PublishedQueryDto publishedQueryDto = PublishedQueryDto.builder()
                .targetType(Published.TargetType.Agent)
                .pageSize(1000)
                .page(1)
                .spaceIds(spaceIds)
                .showRecommend(false)
                .justReturnSpaceData(true).build();
        SuperPage<PublishedDto> page = publishApplicationService.queryPublishedList(publishedQueryDto);
        if (CollectionUtils.isNotEmpty(page.getRecords())) {
            //spaceDtos以ID为key转map
            Map<Long, SpaceDto> spaceMap = spaceDtos.stream().collect(Collectors.toMap(SpaceDto::getId, spaceDto -> spaceDto));
            //以spaceId为key转map
            Map<Long, List<PublishedDto>> spaceIdMap = page.getRecords().stream().collect(Collectors.groupingBy(PublishedDto::getSpaceId));
            for (Map.Entry<Long, List<PublishedDto>> entry : spaceIdMap.entrySet()) {
                Long spaceId = entry.getKey();
                List<PublishedDto> publishedDtos = entry.getValue();
                if (CollectionUtils.isNotEmpty(publishedDtos)) {
                    List<HomeItemDto> homeItemDtos = publishedDtos.stream().map(publishedDto -> convertToHomeItemDto(publishedDto, userAgentSortMap.get(HomeCategoryDto.getSpaceTypeName(spaceId)), usedAgentMap)).collect(Collectors.toList());
                    homeItemDtos.sort(Comparator.comparing(HomeItemDto::getSort));
                    Integer sort = getCategorySort(userAgentCategorySortMap, HomeCategoryDto.getSpaceTypeName(spaceId));
                    SpaceDto spaceDto = spaceMap.get(spaceId);
                    if (spaceDto != null) {
                        homeItemListDto.getCategories().add(HomeCategoryDto.builder().type(HomeCategoryDto.getSpaceTypeName(spaceId))
                                .categoryType(spaceDto.getType() == Space.Type.Personal ? HomeCategoryDto.HomeCategoryTypeEnum.PERSONAL_SPACE : HomeCategoryDto.HomeCategoryTypeEnum.TEAM_SPACE)
                                .icon(spaceDto.getIcon())
                                .name(spaceMap.get(spaceId).getName()).sort(sort).build());
                    }
                    homeItemListDto.getCategoryItems().put(HomeCategoryDto.getSpaceTypeName(spaceId), homeItemDtos);
                }
            }
        }

        if (homeItemListDto.getCategories().isEmpty()) {
            homeItemListDto.getCategories().add(HomeCategoryDto.builder().type(HomeCategoryDto.HomeCategoryTypeEnum.AGENT_COLLECT.name()).name(I18nUtil.systemMessage("Backend.Home.Category.AgentCollect")).build());
        }
        homeItemListDto.getCategories().sort(Comparator.comparing(HomeCategoryDto::getSort));
        homeItemListDto.getCategoryItems().values().forEach(list -> list.forEach(homeItemDto -> homeItemDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(homeItemDto.getIcon(), homeItemDto.getName()))));
        return ReqResult.success(homeItemListDto);
    }

    private Integer getAgentSort(Map<Long, Integer> longIntegerMap, Long agentId) {
        return longIntegerMap != null && longIntegerMap.containsKey(agentId) ? longIntegerMap.get(agentId) : Integer.MAX_VALUE;
    }

    private Integer getCategorySort(Map<String, UserAgentSort> userAgentCategorySortMap, String name) {
        Integer sort = Integer.MAX_VALUE;
        UserAgentSort userAgentSort = userAgentCategorySortMap.get(name);
        if (userAgentSort != null) {
            sort = userAgentSort.getSort();
        }
        return sort;
    }

    private HomeItemDto convertToHomeItemDto(PublishedDto publishedDto, Map<Long, Integer> longIntegerMap, Map<Long, UserAgentDto> usedAgentMap) {
        HomeItemDto homeItemDto = new HomeItemDto();
        homeItemDto.setTargetType(Published.TargetType.Agent);
        homeItemDto.setTargetId(publishedDto.getTargetId());
        homeItemDto.setName(publishedDto.getName());
        homeItemDto.setIcon(publishedDto.getIcon());
        homeItemDto.setDescription(publishedDto.getDescription());
        homeItemDto.setPublishUser(publishedDto.getPublishUser());
        homeItemDto.setStatistics(publishedDto.getStatistics());
        homeItemDto.setCollect(publishedDto.isCollect());
        homeItemDto.setSort(getAgentSort(longIntegerMap, publishedDto.getTargetId()));
        homeItemDto.setAgentType(publishedDto.getAgentType());
        UserAgentDto userUsedAgentDto = usedAgentMap.get(publishedDto.getTargetId());
        if (userUsedAgentDto != null && userUsedAgentDto.getLastConversationId() != null) {
            homeItemDto.setLastConversationId(userUsedAgentDto.getLastConversationId());
        }
        return homeItemDto;
    }

}
