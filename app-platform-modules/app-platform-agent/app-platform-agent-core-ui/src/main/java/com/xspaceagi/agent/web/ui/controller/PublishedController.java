package com.xspaceagi.agent.web.ui.controller;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.agent.core.adapter.application.*;
import com.xspaceagi.agent.core.adapter.dto.*;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.adapter.repository.entity.PublishedStatistics;
import com.xspaceagi.agent.core.domain.service.ConversationDomainService;
import com.xspaceagi.agent.core.infra.rpc.CategoryRpcService;
import com.xspaceagi.agent.web.ui.controller.base.BaseController;
import com.xspaceagi.agent.web.ui.controller.dto.PluginDetailDto;
import com.xspaceagi.agent.web.ui.controller.dto.SkillDetailDto;
import com.xspaceagi.agent.web.ui.controller.dto.UserOffShelfDto;
import com.xspaceagi.agent.web.ui.controller.dto.WorkflowDetailDto;
import com.xspaceagi.agent.web.ui.dto.PublishedCategoryDto;
import com.xspaceagi.agent.web.ui.dto.PublishedComposeTableQueryDto;
import com.xspaceagi.agent.web.ui.dto.PublishedKnowledgeQueryDto;
import com.xspaceagi.compose.sdk.request.QueryDorisTableDefinePageRequest;
import com.xspaceagi.compose.sdk.service.IComposeDbTableRpcService;
import com.xspaceagi.knowledge.sdk.request.KnowledgeConfigRequestVo;
import com.xspaceagi.knowledge.sdk.sevice.IKnowledgeConfigRpcService;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.util.DefaultIconUrlUtil;
import com.xspaceagi.system.domain.log.LogPrint;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.sdk.operate.ActionType;
import com.xspaceagi.system.sdk.operate.OperationLogReporter;
import com.xspaceagi.system.sdk.operate.SystemEnum;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.sdk.service.dto.CategoryTypeEnum;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.page.SuperPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Tag(name = "已发布的智能体、组件查询及收藏相关接口")
@RestController
@RequestMapping("/api/published")
@Slf4j
public class PublishedController extends BaseController {

    @Resource
    private PublishApplicationService publishApplicationService;

    @Resource
    private CollectApplicationService collectApplicationService;


    @Resource
    private IKnowledgeConfigRpcService knowledgeConfigRpcService;

    @Resource
    private AgentApplicationService agentApplicationService;

    /**
     * 数据表查询服务
     */
    @Resource
    private IComposeDbTableRpcService composeDbTableRpcService;

    @Resource
    private SpacePermissionService spacePermissionService;

    @Resource
    private ConversationApplicationService conversationApplicationService;

    //临时使用，修复统计错误
    @Resource
    private ConversationDomainService conversationDomainService;

    @Resource
    private WorkflowApplicationService workflowApplicationService;

    @Resource
    private SkillApplicationService skillApplicationService;

    @Resource
    private CategoryRpcService categoryRpcService;

    @Operation(summary = "广场-智能体与插件分类")
    @RequestMapping(path = "/category/list", method = RequestMethod.GET)
    public ReqResult<List<PublishedCategoryDto>> categoryList() {
        List<com.xspaceagi.system.sdk.service.dto.CategoryDto> agentCategories = categoryRpcService.listByTypeAndTenantId(CategoryTypeEnum.AGENT.getCode(), RequestContext.get().getTenantId());
        List<com.xspaceagi.system.sdk.service.dto.CategoryDto> pageAppCategories = categoryRpcService.listByTypeAndTenantId(CategoryTypeEnum.PAGE_APP.getCode(), RequestContext.get().getTenantId());
        List<com.xspaceagi.system.sdk.service.dto.CategoryDto> componentCategories = categoryRpcService.listByTypeAndTenantId(CategoryTypeEnum.COMPONENT.getCode(), RequestContext.get().getTenantId());
        List<PublishedCategoryDto> categoryDtoList = new ArrayList<>();
        //智能体
        categoryDtoList.add(buildTypeCategories(PublishedCategoryDto.CategoryType.Agent, "智能体", agentCategories));

        //PageApp
        categoryDtoList.add(buildTypeCategories(PublishedCategoryDto.CategoryType.PageApp, "网页应用", pageAppCategories));

        //插件分类
        categoryDtoList.add(buildTypeCategories(PublishedCategoryDto.CategoryType.Plugin, "插件", componentCategories));

        //工作流分类
        categoryDtoList.add(buildTypeCategories(PublishedCategoryDto.CategoryType.Workflow, "工作流", componentCategories));

        categoryDtoList.add(buildTypeCategories(PublishedCategoryDto.CategoryType.Template, "模板", componentCategories));

        //技能分类
        categoryDtoList.add(buildTypeCategories(PublishedCategoryDto.CategoryType.Skill, "技能", componentCategories));

        return ReqResult.success(categoryDtoList);
    }

    private PublishedCategoryDto buildTypeCategories(PublishedCategoryDto.CategoryType type, String label, List<com.xspaceagi.system.sdk.service.dto.CategoryDto> categories) {
        PublishedCategoryDto category = new PublishedCategoryDto();
        category.setType(type);
        category.setKey(type.name());
        category.setLabel(label);
        category.setIcon("");
        category.setChildren(categories.stream().map(categoryDto -> {
            PublishedCategoryDto publishedCategoryDto = new PublishedCategoryDto();
            publishedCategoryDto.setKey(categoryDto.getCode());
            publishedCategoryDto.setLabel(categoryDto.getName());
            publishedCategoryDto.setIcon("");
            publishedCategoryDto.setType(PublishedCategoryDto.CategoryType.PageApp);
            return publishedCategoryDto;
        }).toList());
        return category;
    }

    @Operation(summary = "广场-已发布智能体列表接口")
    @RequestMapping(path = "/agent/list", method = RequestMethod.POST)
    public ReqResult<SuperPage<PublishedDto>> agentList(@RequestBody PublishedQueryDto publishedQueryDto) {
        // 如果targetSubType字段不为空，将作为查询条件
        publishedQueryDto.setTargetType(Published.TargetType.Agent);
        if ("Agent".equals(publishedQueryDto.getCategory())) {
            publishedQueryDto.setCategory(null);
        }
        if (publishedQueryDto.getAllowCopy() == null || publishedQueryDto.getAllowCopy().equals(YesOrNoEnum.N.getKey())) {
            publishedQueryDto.setOnlyTemplate(YesOrNoEnum.N.getKey());
        } else if (publishedQueryDto.getAllowCopy().equals(YesOrNoEnum.Y.getKey())) {
            publishedQueryDto.setOnlyTemplate(null);
        } else {
            publishedQueryDto.setOnlyTemplate(YesOrNoEnum.Y.getKey());
        }
        if (publishedQueryDto.getSpaceId() != null) {
            //查询用户有权限的空间,限制访问空间,比如工作流查询全部知识库,要限制用户有权限的空间下的知识库
            var spaceIds = this.obtainAuthSpaceIds();
            if (spaceIds == null || !spaceIds.contains(publishedQueryDto.getSpaceId())) {
                publishedQueryDto.setSpaceId(null);
            }
        }
        if (publishedQueryDto.getShowRecommend() == null) {
            publishedQueryDto.setShowRecommend(true);
        }
        completeOfficialTargetIds(publishedQueryDto);
        SuperPage<PublishedDto> page = publishApplicationService.queryPublishedList(publishedQueryDto);
        page.getRecords().forEach(publishedDto -> {
            publishedDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(publishedDto.getIcon(), publishedDto.getName()));
        });
        if (publishedQueryDto.getUpdateStatics() != null && publishedQueryDto.getUpdateStatics()) {
            page.getRecords().forEach(publishedDto -> {
                long ct = conversationDomainService.agentUserCount(null, publishedDto.getTargetId());
                Long userCount = publishedDto.getStatistics().getUserCount();
                if (userCount != null) {
                    publishApplicationService.incStatisticsCount(Published.TargetType.Agent, publishedDto.getTargetId(), PublishedStatistics.Key.USER_COUNT.getKey(), ct - userCount);
                }
            });
        }
        return ReqResult.success(page);
    }

    private void completeOfficialTargetIds(PublishedQueryDto publishedQueryDto) {
        if (publishedQueryDto.getOfficial() == null || !publishedQueryDto.getOfficial()) {
            publishedQueryDto.setTargetIds(null);
            return;
        }
        publishedQueryDto.setShowRecommend(false);
        TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
        if (publishedQueryDto.getTargetType() == Published.TargetType.Agent) {
            publishedQueryDto.setTargetIds(tenantConfigDto.getOfficialAgentIds());
        }
        if (publishedQueryDto.getTargetType() == Published.TargetType.Plugin && tenantConfigDto.getOfficialPluginIds() != null) {
            List<Long> ids = parseToLongIds(tenantConfigDto.getOfficialPluginIds());
            publishedQueryDto.setTargetIds(ids);
        }
        if (publishedQueryDto.getTargetType() == Published.TargetType.Workflow && tenantConfigDto.getOfficialWorkflowIds() != null) {
            List<Long> ids = parseToLongIds(tenantConfigDto.getOfficialWorkflowIds());
            publishedQueryDto.setTargetIds(ids);
        }
        if (publishedQueryDto.getTargetType() == Published.TargetType.Skill && tenantConfigDto.getOfficialSkillIds() != null) {
            List<Long> ids = parseToLongIds(tenantConfigDto.getOfficialSkillIds());
            publishedQueryDto.setTargetIds(ids);
        }
        if (CollectionUtils.isEmpty(publishedQueryDto.getTargetIds())) {
            publishedQueryDto.setTargetIds(List.of(-1L));
        }
    }

    private List<Long> parseToLongIds(String officialIds) {
        List<Long> ids = new ArrayList<>();
        String[] split = officialIds.split(",");
        for (String s : split) {
            try {
                ids.add(Long.parseLong(s));
            } catch (NumberFormatException ignored) {
            }
        }
        return ids;
    }

    @Operation(summary = "已发布插件列表接口（广场以及弹框选择中全部插件）")
    @RequestMapping(path = "/plugin/list", method = RequestMethod.POST)
    public ReqResult<SuperPage<PublishedDto>> pluginList(@RequestBody PublishedQueryDto publishedQueryDto) {
        publishedQueryDto.setTargetType(Published.TargetType.Plugin);
        if (publishedQueryDto.getAllowCopy() == null || publishedQueryDto.getAllowCopy().equals(YesOrNoEnum.N.getKey())) {
            publishedQueryDto.setOnlyTemplate(YesOrNoEnum.N.getKey());
        }
        if ("Plugin".equals(publishedQueryDto.getCategory())) {
            publishedQueryDto.setCategory(null);
        }
        //查询用户有权限的空间,限制访问空间,比如工作流查询全部知识库,要限制用户有权限的空间下的知识库
        if (publishedQueryDto.getSpaceId() != null) {
            //查询用户有权限的空间,限制访问空间,比如工作流查询全部知识库,要限制用户有权限的空间下的知识库
            var spaceIds = this.obtainAuthSpaceIds();
            if (spaceIds == null || !spaceIds.contains(publishedQueryDto.getSpaceId())) {
                publishedQueryDto.setSpaceId(null);
            }
        }
        completeOfficialTargetIds(publishedQueryDto);
        SuperPage<PublishedDto> page = publishApplicationService.queryPublishedList(publishedQueryDto);
        page.getRecords().forEach(publishedDto -> {
            publishedDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(publishedDto.getIcon(), publishedDto.getName(), Published.TargetType.Plugin.name()));
        });

        return ReqResult.success(page);
    }

    @Operation(summary = "已发布模板列表接口")
    @RequestMapping(path = "/template/list", method = RequestMethod.POST)
    public ReqResult<SuperPage<PublishedDto>> templateList(@RequestBody PublishedQueryDto publishedQueryDto) {
        publishedQueryDto.setAllowCopy(YesOrNoEnum.Y.getKey());
        if ("Template".equals(publishedQueryDto.getCategory())) {
            publishedQueryDto.setCategory(null);
        }
        publishedQueryDto.setShowRecommend(false);
        //查询用户有权限的空间,限制访问空间,比如工作流查询全部知识库,要限制用户有权限的空间下的知识库
        if (publishedQueryDto.getSpaceId() != null) {
            //查询用户有权限的空间,限制访问空间,比如工作流查询全部知识库,要限制用户有权限的空间下的知识库
            var spaceIds = this.obtainAuthSpaceIds();
            if (spaceIds == null || !spaceIds.contains(publishedQueryDto.getSpaceId())) {
                publishedQueryDto.setSpaceId(null);
            }
        }
        ReqResult<SuperPage<PublishedDto>> pageReqResult;
        if (publishedQueryDto.getTargetType() == null) {
            // 智能体模板
            publishedQueryDto.setTargetType(Published.TargetType.Agent);
            completeOfficialTargetIds(publishedQueryDto);
            ReqResult<SuperPage<PublishedDto>> agentPageReqResult = agentList(publishedQueryDto);
            long total = agentPageReqResult.getData().getTotal();

            // 工作流模板
            publishedQueryDto.setTargetType(Published.TargetType.Workflow);
            completeOfficialTargetIds(publishedQueryDto);
            ReqResult<SuperPage<PublishedDto>> workflowPageReqResult = workflowList(publishedQueryDto);
            total += workflowPageReqResult.getData().getTotal();

            // 技能模板
            publishedQueryDto.setTargetType(Published.TargetType.Skill);
            completeOfficialTargetIds(publishedQueryDto);
            ReqResult<SuperPage<PublishedDto>> skillPageReqResult = skillList(publishedQueryDto);
            total += skillPageReqResult.getData().getTotal();

            List<PublishedDto> publishedDtos = new ArrayList<>();
            publishedDtos.addAll(agentPageReqResult.getData().getRecords());
            publishedDtos.addAll(workflowPageReqResult.getData().getRecords());
            publishedDtos.addAll(skillPageReqResult.getData().getRecords());

            // 按修改时间排序
            publishedDtos.sort(Comparator.comparing(PublishedDto::getModified).reversed());
            SuperPage<PublishedDto> iPage = new SuperPage<>(publishedQueryDto.getPage(), publishedQueryDto.getPageSize(), total, publishedDtos);
            pageReqResult = ReqResult.success(iPage);
        } else {
            completeOfficialTargetIds(publishedQueryDto);
            pageReqResult = ReqResult.success(publishApplicationService.queryPublishedList(publishedQueryDto));
        }
        pageReqResult.getData().getRecords().forEach(publishedDto -> publishedDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(publishedDto.getIcon(), publishedDto.getName(), publishedDto.getTargetType().name())));
        return pageReqResult;
    }

    @Operation(summary = "已发布的智能体详情接口")
    @RequestMapping(path = "/agent/{agentId}", method = RequestMethod.GET)
    public ReqResult<AgentDetailDto> agentDetail(@PathVariable Long agentId, @RequestParam(required = false) Boolean withConversationId) {
        AgentDetailDto agentDetailDto = agentApplicationService.queryAgentDetail(agentId, true);
        if (agentDetailDto == null) {
            throw new BizException("智能体未发布或已下架");
        }
        PublishedPermissionDto publishedPermissionDto = publishApplicationService.hasPermission(Published.TargetType.Agent, agentId);
        if (!publishedPermissionDto.isView()) {
            return ReqResult.error("无智能体会话权限");
        }
        agentDetailDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(agentDetailDto.getIcon(), agentDetailDto.getName()));

        if (withConversationId != null && withConversationId && !"PageApp".equals(agentDetailDto.getType())) {
            agentDetailDto.setConversationId(conversationApplicationService.nextConversationId(agentId, agentDetailDto.getSandboxId() == null ? null : agentDetailDto.getSandboxId().toString()));
        }

        return ReqResult.success(agentDetailDto);
    }

    @Operation(summary = "已发布的插件详情接口")
    @RequestMapping(path = "/plugin/{pluginId}", method = RequestMethod.GET)
    public ReqResult<PluginDetailDto> pluginDetail(@PathVariable Long pluginId) {
        PublishedDto publishedDto = publishApplicationService.queryPublished(Published.TargetType.Plugin, pluginId);
        if (publishedDto == null) {
            throw new BizException("插件插件不存在或已下架");
        }
        PublishedPermissionDto publishedPermissionDto = publishApplicationService.hasPermission(Published.TargetType.Plugin, pluginId);
        if (!publishedPermissionDto.isView()) {
            return ReqResult.error("无插件查看权限");
        }
        UserDto userDto = (UserDto) RequestContext.get().getUser();
        PluginDto pluginDto = JSON.parseObject(publishedDto.getConfig(), PluginDto.class);
        PluginDetailDto pluginDetailDto = new PluginDetailDto();
        if (pluginDto != null && pluginDto.getConfig() != null) {
            PluginConfigDto pluginConfigDto = JSON.parseObject(pluginDto.getConfig().toString(), PluginConfigDto.class);
            pluginDetailDto.setId(pluginId);
            pluginDetailDto.setName(pluginDto.getName());
            pluginDetailDto.setDescription(pluginDto.getDescription());
            pluginDetailDto.setIcon(pluginDto.getIcon());
            //管理员可以查看所有参数
            if (pluginConfigDto.getInputArgs() != null && userDto.getRole() != User.Role.Admin) {
                pluginConfigDto.getInputArgs().removeIf(arg -> !arg.getEnable());
            }
            pluginDetailDto.setInputArgs(pluginConfigDto.getInputArgs());
            pluginDetailDto.setOutputArgs(pluginConfigDto.getOutputArgs());
            pluginDetailDto.setRemark(publishedDto.getRemark());
            pluginDetailDto.setCollect(publishedDto.isCollect());
            pluginDetailDto.setStatistics(publishedDto.getStatistics());
            pluginDetailDto.setPublishUser(publishedDto.getPublishUser());
            pluginDetailDto.setCreated(publishedDto.getCreated());
            pluginDetailDto.setAllowCopy(publishedDto.getAllowCopy());
            pluginDetailDto.setCategory(publishedDto.getCategory());
        }
        pluginDetailDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(pluginDetailDto.getIcon(), pluginDetailDto.getName(), Published.TargetType.Plugin.name()));
        return ReqResult.success(pluginDetailDto);
    }

    @Operation(summary = "已发布的工作流详情接口")
    @RequestMapping(path = "/workflow/{workflowId}", method = RequestMethod.GET)
    public ReqResult<WorkflowDetailDto> workflowDetail(@PathVariable Long workflowId) {
        PublishedDto publishedDto = publishApplicationService.queryPublished(Published.TargetType.Workflow, workflowId);
        if (publishedDto == null) {
            throw new BizException("工作流不存在或已下架");
        }
        setCopyPermission(publishedDto);
        WorkflowConfigDto workflowConfigDto = workflowApplicationService.queryPublishedWorkflowConfig(workflowId, null, false);
        WorkflowDetailDto workflowDetailDto = new WorkflowDetailDto();
        if (workflowConfigDto != null) {
            workflowDetailDto.setId(workflowId);
            workflowDetailDto.setDescription(workflowConfigDto.getDescription());
            workflowDetailDto.setIcon(workflowConfigDto.getIcon());
            workflowDetailDto.setName(workflowConfigDto.getName());
            if (workflowDetailDto.getInputArgs() != null) {
                workflowDetailDto.getInputArgs().removeIf(arg -> !arg.getEnable());
            }
            workflowDetailDto.setInputArgs(workflowConfigDto.getInputArgs());
            workflowDetailDto.setOutputArgs(workflowConfigDto.getOutputArgs());
            workflowDetailDto.setRemark(publishedDto.getRemark());
            workflowDetailDto.setStatistics(publishedDto.getStatistics());
            workflowDetailDto.setPublishUser(publishedDto.getPublishUser());
            workflowDetailDto.setCollect(publishedDto.isCollect());
            workflowDetailDto.setCreated(publishedDto.getCreated());
            workflowDetailDto.setCategory(publishedDto.getCategory());
            workflowDetailDto.setAllowCopy(publishedDto.getAllowCopy());
        }
        workflowDetailDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(workflowDetailDto.getIcon(), workflowDetailDto.getName(), Published.TargetType.Workflow.name()));
        return ReqResult.success(workflowDetailDto);
    }

    @OperationLogReporter(actionType = ActionType.QUERY,
            action = "已发布数据表列表接口", objectName = "已发布的智能体查询相关接口", systemCode = SystemEnum.AGENT)
    @LogPrint(step = "已发布数据表列表接口")
    @Operation(summary = "已发布数据表列表接口（广场以及弹框选择中全部插件）")
    @RequestMapping(path = "/composeTable/list", method = RequestMethod.POST)
    public ReqResult<SuperPage<PublishedDto>> composeTableList(
            @RequestBody PublishedComposeTableQueryDto publishedQueryDto) {
        publishedQueryDto.setTargetType(Published.TargetType.Knowledge);
        publishedQueryDto.setChannel(Published.PublishChannel.Space);

        // 查询用户有权限的空间,限制访问空间,比如工作流查询全部知识库,要限制用户有权限的空间下的知识库
        var spaceIds = this.obtainAuthSpaceIds();
        if (publishedQueryDto.getSpaceId() != null) {
            if (spaceIds == null || !spaceIds.contains(publishedQueryDto.getSpaceId())) {
                publishedQueryDto.setSpaceId(null);
            }
        }
        QueryDorisTableDefinePageRequest queryDorisTableDefinePageRequest = QueryDorisTableDefinePageRequest.builder()
                .spaceId(publishedQueryDto.getSpaceId())
                .pageNo(publishedQueryDto.getPage())
                .pageSize(publishedQueryDto.getPageSize())
                .kw(publishedQueryDto.getKw())
                .authSpaceIds(spaceIds)
                .build();

        var voPage = this.composeDbTableRpcService.queryTableDefineBySpaceId(queryDorisTableDefinePageRequest);
        var voList = voPage.getRecords();
        var dataList = voList.stream()
                .map(PublishedComposeTableQueryDto::convertFromComposeTableVo)
                .toList();
        var pageNo = voPage.getCurrent();
        var pageSize = voPage.getSize();
        var total = voPage.getTotal();
        SuperPage<PublishedDto> iPage = new SuperPage<>(pageNo, pageSize, total, dataList);

        return ReqResult.success(iPage);
    }


    @OperationLogReporter(actionType = ActionType.QUERY,
            action = "已发布知识库列表接口", objectName = "已发布的智能体查询相关接口", systemCode = SystemEnum.AGENT)
    @LogPrint(step = "已发布知识库列表接口")
    @Operation(summary = "已发布知识库列表接口（广场以及弹框选择中全部插件）")
    @RequestMapping(path = "/knowledge/list", method = RequestMethod.POST)
    public ReqResult<SuperPage<PublishedDto>> knowledgeList(@RequestBody PublishedKnowledgeQueryDto publishedQueryDto) {
        publishedQueryDto.setTargetType(Published.TargetType.Knowledge);
        publishedQueryDto.setChannel(Published.PublishChannel.Space);


        //查询用户有权限的空间,限制访问空间,比如工作流查询全部知识库,要限制用户有权限的空间下的知识库
        var spaceIds = this.obtainAuthSpaceIds();
        KnowledgeConfigRequestVo knowledgeConfigRequestVo = KnowledgeConfigRequestVo.builder()
                .kw(publishedQueryDto.getKw())
                .spaceId(publishedQueryDto.getSpaceId())
                .authSpaceIds(spaceIds)
                .dataType(publishedQueryDto.getDataType())
                .page(publishedQueryDto.getPage())
                .pageSize(publishedQueryDto.getPageSize())
                .build();

        var voResponse = this.knowledgeConfigRpcService.queryListKnowledgeConfig(knowledgeConfigRequestVo);
        var voPage = voResponse.getConfigPage();
        var voList = voPage.getRecords();
        var dataList = voList.stream()
                .map(PublishedKnowledgeQueryDto::convertFromKnowledgeConfigVo)
                .toList();
        SuperPage<PublishedDto> iPage = SuperPage.build(voPage, dataList);

        return ReqResult.success(iPage);
    }

    @Operation(summary = "已收藏的插件列表接口")
    @RequestMapping(path = "/plugin/collect/list", method = RequestMethod.POST)
    public ReqResult<List<PublishedDto>> pluginCollectList(@RequestBody PublishedQueryDto publishedQueryDto) {
        List<PublishedDto> publishedDtos = collectApplicationService.queryCollectList(RequestContext.get().getUserId(), Published.TargetType.Plugin, publishedQueryDto.getSpaceId());
        publishedDtos = filter(publishedQueryDto, publishedDtos);
        publishedDtos.forEach(publishedDto -> publishedDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(publishedDto.getIcon(), publishedDto.getName(), Published.TargetType.Plugin.name())));
        return ReqResult.success(publishedDtos);
    }

    @Operation(summary = "收藏插件接口")
    @RequestMapping(path = "/plugin/collect/{pluginId}", method = RequestMethod.POST)
    public ReqResult<Void> pluginCollect(@PathVariable Long pluginId) {
        collectApplicationService.collect(RequestContext.get().getUserId(), Published.TargetType.Plugin, pluginId);
        return ReqResult.success();
    }

    @Operation(summary = "取消收藏插件接口")
    @RequestMapping(path = "/plugin/unCollect/{pluginId}", method = RequestMethod.POST)
    public ReqResult<Void> pluginUnCollect(@PathVariable Long pluginId) {
        collectApplicationService.unCollect(RequestContext.get().getUserId(), Published.TargetType.Plugin, pluginId);
        return ReqResult.success();
    }

    @Operation(summary = "已发布工作流列表接口（弹框选择中全部插件）")
    @RequestMapping(path = "/workflow/list", method = RequestMethod.POST)
    public ReqResult<SuperPage<PublishedDto>> workflowList(@RequestBody PublishedQueryDto publishedQueryDto) {
        publishedQueryDto.setTargetType(Published.TargetType.Workflow);
        if ("Workflow".equals(publishedQueryDto.getCategory())) {
            publishedQueryDto.setCategory(null);
        }
        //查询用户有权限的空间,限制访问空间,比如工作流查询全部知识库,要限制用户有权限的空间下的知识库
        if (publishedQueryDto.getSpaceId() != null) {
            //查询用户有权限的空间,限制访问空间,比如工作流查询全部知识库,要限制用户有权限的空间下的知识库
            var spaceIds = this.obtainAuthSpaceIds();
            if (spaceIds == null || !spaceIds.contains(publishedQueryDto.getSpaceId())) {
                publishedQueryDto.setSpaceId(null);
            }
        }
        if (publishedQueryDto.getAllowCopy() == null || publishedQueryDto.getAllowCopy().equals(YesOrNoEnum.N.getKey())) {
            publishedQueryDto.setOnlyTemplate(YesOrNoEnum.N.getKey());
        } else if (publishedQueryDto.getAllowCopy().equals(YesOrNoEnum.Y.getKey())) {
            publishedQueryDto.setOnlyTemplate(null);
        } else {
            publishedQueryDto.setAllowCopy(YesOrNoEnum.Y.getKey());
        }
        completeOfficialTargetIds(publishedQueryDto);
        SuperPage<PublishedDto> page = publishApplicationService.queryPublishedList(publishedQueryDto);
        page.getRecords().forEach(publishedDto -> publishedDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(publishedDto.getIcon(), publishedDto.getName(), publishedDto.getTargetType().name())));
        return ReqResult.success(page);
    }

    @Operation(summary = "已收藏的工作流列表接口")
    @RequestMapping(path = "/workflow/collect/list", method = RequestMethod.POST)
    public ReqResult<List<PublishedDto>> workflowCollectList(@RequestBody PublishedQueryDto publishedQueryDto) {
        List<PublishedDto> publishedDtos = collectApplicationService.queryCollectList(RequestContext.get().getUserId(), Published.TargetType.Workflow, publishedQueryDto.getSpaceId());
        publishedDtos = filter(publishedQueryDto, publishedDtos);
        publishedDtos.forEach(publishedDto -> publishedDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(publishedDto.getIcon(), publishedDto.getName(), Published.TargetType.Workflow.name())));
        return ReqResult.success(publishedDtos);
    }

    @Operation(summary = "收藏工作流接口")
    @RequestMapping(path = "/workflow/collect/{workflowId}", method = RequestMethod.POST)
    public ReqResult<Void> workflowCollect(@PathVariable Long workflowId) {
        collectApplicationService.collect(RequestContext.get().getUserId(), Published.TargetType.Workflow, workflowId);
        return ReqResult.success();
    }

    @Operation(summary = "取消收藏工作流接口")
    @RequestMapping(path = "/workflow/unCollect/{workflowId}", method = RequestMethod.POST)
    public ReqResult<Void> workflowUnCollect(@PathVariable Long workflowId) {
        collectApplicationService.unCollect(RequestContext.get().getUserId(), Published.TargetType.Workflow, workflowId);
        return ReqResult.success();
    }

    @Operation(summary = "已发布技能列表接口")
    @RequestMapping(path = "/skill/list", method = RequestMethod.POST)
    public ReqResult<SuperPage<PublishedDto>> skillList(@RequestBody PublishedQueryDto publishedQueryDto) {
        publishedQueryDto.setTargetType(Published.TargetType.Skill);
        if ("Skill".equals(publishedQueryDto.getCategory())) {
            publishedQueryDto.setCategory(null);
        }
        if (publishedQueryDto.getAllowCopy() == null || publishedQueryDto.getAllowCopy().equals(YesOrNoEnum.N.getKey())) {
            publishedQueryDto.setOnlyTemplate(YesOrNoEnum.N.getKey());
        } else if (publishedQueryDto.getAllowCopy().equals(YesOrNoEnum.Y.getKey())) {
            publishedQueryDto.setOnlyTemplate(null);
        } else {
            publishedQueryDto.setOnlyTemplate(YesOrNoEnum.Y.getKey());
        }
        //查询用户有权限的空间,限制访问空间
        if (publishedQueryDto.getSpaceId() != null) {
            var spaceIds = this.obtainAuthSpaceIds();
            if (spaceIds == null || !spaceIds.contains(publishedQueryDto.getSpaceId())) {
                publishedQueryDto.setSpaceId(null);
            }
        }
        if (publishedQueryDto.getShowRecommend() == null) {
            publishedQueryDto.setShowRecommend(true);
        }
        completeOfficialTargetIds(publishedQueryDto);
        SuperPage<PublishedDto> page = publishApplicationService.queryPublishedList(publishedQueryDto);
        page.getRecords().forEach(publishedDto -> {
            publishedDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(publishedDto.getIcon(), publishedDto.getName(), Published.TargetType.Skill.name()));
        });
        return ReqResult.success(page);
    }

    @Operation(summary = "查询技能列表-用于@技能")
    @RequestMapping(path = "/skill/list-for-at", method = RequestMethod.POST)
    public ReqResult<SuperPage<PublishedDto>> skillListForAt(@RequestBody PublishedQueryDto publishedQueryDto) {
        PublishedQueryDto queryDto = new PublishedQueryDto();
        queryDto.setTargetType(Published.TargetType.Skill);
        queryDto.setPage(publishedQueryDto.getPage());
        queryDto.setPageSize(publishedQueryDto.getPageSize());
        queryDto.setKw(publishedQueryDto.getKw());
        queryDto.setJustReturnSpaceData(false);
        queryDto.setShowRecommend(false);

        //查询用户有权限的空间
        var spaceIds = this.obtainAuthSpaceIds();
        queryDto.setSpaceIds(spaceIds);

        SuperPage<PublishedDto> page = publishApplicationService.queryPublishedListForAt(publishedQueryDto);
        if (CollectionUtils.isNotEmpty(page.getRecords())) {
            page.getRecords().forEach(publishedDto -> {
                publishedDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(publishedDto.getIcon(), publishedDto.getName(), Published.TargetType.Skill.name()));
            });
        }
        return ReqResult.success(page);
    }

    @Operation(summary = "已发布的技能详情接口")
    @RequestMapping(path = "/skill/{skillId}", method = RequestMethod.GET)
    public ReqResult<SkillDetailDto> skillDetail(@PathVariable Long skillId) {
        PublishedDto publishedDto = publishApplicationService.queryPublished(Published.TargetType.Skill, skillId);
        if (publishedDto == null) {
            throw new BizException("技能不存在或已下架");
        }
        PublishedPermissionDto publishedPermissionDto = publishApplicationService.hasPermission(Published.TargetType.Skill, skillId);
        if (!publishedPermissionDto.isView()) {
            return ReqResult.error("无技能查看权限");
        }
        setCopyPermission(publishedDto);
        SkillConfigDto skillConfigDto = JSON.parseObject(publishedDto.getConfig(), SkillConfigDto.class);
        SkillDetailDto skillDetailDto = new SkillDetailDto();
        if (skillConfigDto != null) {
            skillDetailDto.setId(skillId);
            skillDetailDto.setName(skillConfigDto.getName());
            skillDetailDto.setDescription(skillConfigDto.getDescription());
            skillDetailDto.setIcon(skillConfigDto.getIcon());
            skillDetailDto.setFiles(skillConfigDto.getFiles());
            skillDetailDto.setRemark(publishedDto.getRemark());
            skillDetailDto.setCollect(publishedDto.isCollect());
            skillDetailDto.setStatistics(publishedDto.getStatistics());
            skillDetailDto.setPublishUser(publishedDto.getPublishUser());
            skillDetailDto.setCreated(publishedDto.getCreated());
            skillDetailDto.setAllowCopy(publishedDto.getAllowCopy());
            skillDetailDto.setCategory(publishedDto.getCategory());
        }
        skillDetailDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(skillDetailDto.getIcon(), skillDetailDto.getName(), Published.TargetType.Skill.name()));
        return ReqResult.success(skillDetailDto);
    }

    @Operation(summary = "导出已发布的技能")
    @GetMapping(path = "/skill/export/{skillId}", produces = "application/octet-stream")
    public byte[] skillExport(@PathVariable Long skillId, HttpServletResponse response) {
        PublishedDto publishedDto = publishApplicationService.queryPublished(Published.TargetType.Skill, skillId);
        if (publishedDto == null) {
            throw new BizException("技能不存在或已下架");
        }
        PublishedPermissionDto publishedPermissionDto = publishApplicationService.hasPermission(Published.TargetType.Skill, skillId);
        if (!publishedPermissionDto.isView()) {
            throw new BizException("无技能下载权限");
        }
        setCopyPermission(publishedDto);
        SkillConfigDto skillConfigDto = JSON.parseObject(publishedDto.getConfig(), SkillConfigDto.class);
        SkillExportResultDto exportResult = skillApplicationService.exportSkill(skillConfigDto);

        // 设置响应头
        response.setHeader("Content-Disposition", "attachment;filename=" + URLEncoder.encode(exportResult.getFileName(), Charset.forName("UTF-8")));
        response.setHeader("Access-Control-Expose-Headers", "Content-Disposition");
        return exportResult.getData();
    }

    @Operation(summary = "已收藏的技能列表接口")
    @RequestMapping(path = "/skill/collect/list", method = RequestMethod.POST)
    public ReqResult<List<PublishedDto>> skillCollectList(@RequestBody PublishedQueryDto publishedQueryDto) {
        publishedQueryDto.setPage(1);
        publishedQueryDto.setPageSize(1000);
        //查询用户有权限的空间
        var spaceIds = this.obtainAuthSpaceIds();
        List<PublishedDto> publishedDtos = collectApplicationService.queryCollectListWithoutConfig(RequestContext.get().getUserId(), Published.TargetType.Skill, spaceIds);
        if (CollectionUtils.isNotEmpty(publishedDtos)) {
            publishedDtos = filter(publishedQueryDto, publishedDtos);
            publishedDtos.forEach(publishedDto -> publishedDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(publishedDto.getIcon(), publishedDto.getName(), Published.TargetType.Skill.name())));
        }
        return ReqResult.success(publishedDtos);
    }

    @Operation(summary = "收藏技能接口")
    @RequestMapping(path = "/skill/collect/{skillId}", method = RequestMethod.POST)
    public ReqResult<Void> skillCollect(@PathVariable Long skillId) {
        collectApplicationService.collect(RequestContext.get().getUserId(), Published.TargetType.Skill, skillId);
        return ReqResult.success();
    }

    @Operation(summary = "取消收藏技能接口")
    @RequestMapping(path = "/skill/unCollect/{skillId}", method = RequestMethod.POST)
    public ReqResult<Void> skillUnCollect(@PathVariable Long skillId) {
        collectApplicationService.unCollect(RequestContext.get().getUserId(), Published.TargetType.Skill, skillId);
        return ReqResult.success();
    }

    @Operation(summary = "最近使用的技能列表")
    @RequestMapping(path = "/skill/recentlyUsed/list", method = RequestMethod.POST)
    public ReqResult<List<PublishedDto>> skillRecentlyUsedList(@RequestBody PublishedQueryDto publishedQueryDto) {
        Integer size = publishedQueryDto.getPageSize() != null && publishedQueryDto.getPageSize() > 0 ? publishedQueryDto.getPageSize() : 50;
        List<PublishedDto> publishedDtos = skillApplicationService.queryRecentlyUsedSkills(publishedQueryDto.getKw(), size);
        return ReqResult.success(publishedDtos);
    }

    @Operation(summary = "智能体、插件、工作流下架")
    @RequestMapping(path = "/offShelf", method = RequestMethod.POST)
    public ReqResult<Void> offShelf(@RequestBody UserOffShelfDto offShelfDto) {
        PublishedDto publishedDto = publishApplicationService.queryPublished(offShelfDto.getTargetType(), offShelfDto.getTargetId());
        if (publishedDto == null) {
            throw new BizException("下架失败，未发布");
        }
        spacePermissionService.checkSpaceUserPermission(publishedDto.getSpaceId());
        OffShelfDto offShelfDto1 = new OffShelfDto();
        offShelfDto1.setPublishId(publishedDto.getId());
        offShelfDto1.setReason("用户自行下架");
        publishApplicationService.offShelf(offShelfDto1);
        return ReqResult.success();
    }

    private List<PublishedDto> filter(PublishedQueryDto publishedQueryDto, List<PublishedDto> publishedDtos) {
        //根据PublishedQueryDto中的page和pageSize对publishedDtos分页
        if (publishedQueryDto.getPage() == null || publishedQueryDto.getPage() < 1) {
            publishedQueryDto.setPage(1);
        }
        publishedDtos = publishedDtos.stream().skip((long) (publishedQueryDto.getPage() - 1) * publishedQueryDto.getPageSize()).limit(publishedQueryDto.getPageSize()).toList();
        //如果分类名称不为空，根据分类名称过滤
        if (StringUtils.isNotBlank(publishedQueryDto.getCategory())) {
            publishedDtos = publishedDtos.stream().filter(publishedDto -> publishedDto.getCategory().equals(publishedQueryDto.getCategory())).toList();
        }
        //如果搜索关键词不为空，根据关键词包含过滤
        if (StringUtils.isNotBlank(publishedQueryDto.getKw())) {
            publishedDtos = publishedDtos.stream().filter(publishedDto -> publishedDto.getName().contains(publishedQueryDto.getKw())).toList();
        }
        return publishedDtos;
    }

    private void setCopyPermission(PublishedDto publishedDto) {
        if (publishedDto.getScope() == Published.PublishScope.Tenant && Objects.equals(publishedDto.getAllowCopy(), YesOrNoEnum.Y.getKey())) {
            return;
        }
        PublishedPermissionDto publishedPermissionDto = publishApplicationService.hasPermission(publishedDto.getTargetType(), publishedDto.getTargetId());
        if (!publishedPermissionDto.isView()) {
            throw new BizException("无查看权限");
        }
        publishedDto.setAllowCopy(publishedPermissionDto.isCopy() ? YesOrNoEnum.Y.getKey() : YesOrNoEnum.N.getKey());
    }

}
