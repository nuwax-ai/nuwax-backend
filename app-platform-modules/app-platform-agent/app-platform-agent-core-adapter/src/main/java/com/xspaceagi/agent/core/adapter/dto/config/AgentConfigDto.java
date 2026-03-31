package com.xspaceagi.agent.core.adapter.dto.config;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.agent.core.adapter.dto.CreatorDto;
import com.xspaceagi.agent.core.adapter.dto.GuidQuestionDto;
import com.xspaceagi.agent.core.adapter.dto.PublishUserDto;
import com.xspaceagi.agent.core.adapter.dto.StatisticsDto;
import com.xspaceagi.agent.core.adapter.dto.config.bind.*;
import com.xspaceagi.agent.core.adapter.repository.entity.AgentComponentConfig;
import com.xspaceagi.agent.core.adapter.repository.entity.AgentConfig;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.system.application.dto.SpaceDto;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AgentConfigDto implements Serializable {

    @Schema(description = "智能体ID")
    private Long id; // 智能体ID

    @Schema(description = "agent唯一标识")
    private String uid; // agent唯一标识

    @Schema(description = "类型，ChatBot 对话智能体；PageApp 网页应用智能体, TaskAgent 任务型智能体")
    private String type;

    @Schema(description = "商户ID")
    private Long tenantId; // 商户ID

    @Schema(description = "空间ID")
    private Long spaceId; // 空间ID

    @Schema(description = "创建者ID")
    private Long creatorId; // 创建者ID

    @Schema(description = "Agent名称")
    private String name; // Agent名称

    @Schema(description = "Agent描述")
    private String description; // Agent描述

    @Schema(description = "图标地址")
    private String icon; // 图标地址

    @Schema(description = "系统提示词")
    private String systemPrompt; // 系统提示词

    @Schema(description = "用户消息提示词")
    private String userPrompt; // 用户消息提示词，{{AGENT_USER_MSG}}引用用户消息

    @Schema(description = "是否开启问题建议")
    private AgentConfig.OpenStatus openSuggest; // 是否开启问题建议

    @Schema(description = "问题建议提示词")
    private String suggestPrompt; // 用户问题建议

    @Schema(description = "首次打开聊天框自动回复消息")
    private String openingChatMsg; // 首次打开聊天框自动回复消息

    @Schema(description = "首次打开引导问题（弃用）")
    private List<String> openingGuidQuestions; // 开场引导问题

    @Schema(description = "引导问题")
    private List<GuidQuestionDto> guidQuestionDtos;

    @Schema(description = "是否开启长期记忆")
    private AgentConfig.OpenStatus openLongMemory; // 是否开启长期记忆

    @Schema(description = "发布状态")
    private Published.PublishStatus publishStatus; // Agent发布状态

    @Schema(description = "最后编辑时间")
    private Date modified; // 更新时间

    @Schema(description = "创建时间")
    private Date created; // 创建时间

    @Schema(description = "模型信息")
    private AgentComponentConfigDto modelComponentConfig; // 模型

    @Schema(description = "组件配置列表", hidden = true)
    private List<AgentComponentConfigDto> agentComponentConfigList;

    @Schema(description = "统计信息")
    private StatisticsDto agentStatistics;

    @Schema(description = "发布者信息（若已发布）")
    private PublishUserDto publishUser;

    @Schema(description = "创建者信息")
    private CreatorDto creator;

    @Schema(description = "空间信息")
    private SpaceDto space;

    @Schema(description = "是否开发收藏")
    private boolean isDevCollected;

    @Schema(description = "是否收藏", hidden = true)
    private boolean isCollected;

    @Schema(description = "开发会话ID")
    private Long devConversationId;

    @Schema(description = "发布时间，如果不为空，与当前modified时间做对比，如果发布时间小于modified，则前端显示：有更新未发布")
    private Date publishDate;

    @Schema(description = "发布备注")
    private String publishRemark;

    @Schema(description = "智能体分类名称")
    private String category;

    @Schema(description = "是否允许复制, 1 允许", hidden = true)
    private Integer allowCopy;

    @Schema(description = "是否开启定时任务")
    private AgentConfig.OpenStatus openScheduledTask;

    @Schema(description = "权限列表")
    private List<String> permissions;

    @Schema(description = "是否默认展开扩展页面区域, 1 展开；0 不展开")
    private Integer expandPageArea;

    @Schema(description = "是否隐藏聊天区域，1 隐藏；0 不隐藏")
    private Integer hideChatArea;

    @Schema(description = "扩展页面首页")
    private String pageHomeIndex;

    @Schema(description = "扩展信息", hidden = true)
    private Map<String, Object> extra;

    @Schema(description = "访问控制, 0 不受管控；1 管控")
    private Integer accessControl;

    @Schema(description = "是否隐藏远程桌面, 1 隐藏；0 不隐藏")
    private Integer hideDesktop;

    @Schema(description = "代理MCP SSE地址", hidden = true)
    private String proxyMcpServerConfig;

    @Schema(description = "是否允许用户在对话框中选择其他模型, 1 允许，其他不允许")
    private Integer allowOtherModel;

    @Schema(description = "是否允许用户在对话框中@技能， 1 允许，其他不允许")
    private Integer allowAtSkill;

    @Schema(description = "是否允许用户在对话框中选择自己的电脑， 1 允许，其他不允许")
    private Integer allowPrivateSandbox;

    public String getPageHomeIndex() {
        //设置默认页面首页
        if (getAgentComponentConfigList() != null) {
            Optional<AgentComponentConfigDto> first = getAgentComponentConfigList().stream().filter(componentConfig -> componentConfig.getType().equals(AgentComponentConfig.Type.Page)).findFirst();
            if (first.isPresent()) {
                PageBindConfigDto pageBindConfigDto = (PageBindConfigDto) first.get().getBindConfig();
                if (pageBindConfigDto != null) {
                    //没有设置默认首页时，第一个为首页
                    setPageHomeIndex("/page" + pageBindConfigDto.getBasePath() + "-" + getId() + "/prod/");
                }
            }
            for (AgentComponentConfigDto componentConfigDto : getAgentComponentConfigList()) {
                if (componentConfigDto.getType().equals(AgentComponentConfig.Type.Page)) {
                    PageBindConfigDto pageBindConfigDto = (PageBindConfigDto) componentConfigDto.getBindConfig();
                    if (pageBindConfigDto != null && pageBindConfigDto.getHomeIndex() == 1) {
                        setPageHomeIndex("/page" + pageBindConfigDto.getBasePath() + "-" + getId() + "/prod/");
                        break;
                    }
                }
            }
        }
        return pageHomeIndex;
    }

    public static void convertBindConfig(AgentComponentConfigDto componentConfig) {
        if (componentConfig.getType() == null) {
            return;
        }
        if (componentConfig.getBindConfig() instanceof JSONObject) {
            switch (componentConfig.getType()) {
                case Model -> {
                    ModelBindConfigDto modelBindConfigDto = JSON.parseObject(componentConfig.getBindConfig().toString(), ModelBindConfigDto.class);
                    componentConfig.setBindConfig(modelBindConfigDto);
                }
                case Plugin -> {
                    PluginBindConfigDto pluginBindConfigDto = JSON.parseObject(componentConfig.getBindConfig().toString(), PluginBindConfigDto.class);
                    componentConfig.setBindConfig(pluginBindConfigDto);
                }
                case Workflow -> {
                    WorkflowBindConfigDto workflowBindConfigDto = JSON.parseObject(componentConfig.getBindConfig().toString(), WorkflowBindConfigDto.class);
                    componentConfig.setBindConfig(workflowBindConfigDto);
                }
                case Table -> {
                    TableBindConfigDto tableBindConfigDto = JSON.parseObject(componentConfig.getBindConfig().toString(), TableBindConfigDto.class);
                    componentConfig.setBindConfig(tableBindConfigDto);
                }
                case Knowledge -> {
                    KnowledgeBaseBindConfigDto knowledgeBaseBindConfigDto = JSON.parseObject(componentConfig.getBindConfig().toString(), KnowledgeBaseBindConfigDto.class);
                    componentConfig.setBindConfig(knowledgeBaseBindConfigDto);
                }
                case Trigger -> {
                    TriggerConfigDto triggerConfigDto = JSON.parseObject(componentConfig.getBindConfig().toString(), TriggerConfigDto.class);
                    componentConfig.setBindConfig(triggerConfigDto);
                }
                case Variable -> {
                    VariableConfigDto variableConfigDto = JSON.parseObject(componentConfig.getBindConfig().toString(), VariableConfigDto.class);
                    componentConfig.setBindConfig(variableConfigDto);
                }
                case Mcp -> {
                    McpBindConfigDto mcpBindConfigDto = JSON.parseObject(componentConfig.getBindConfig().toString(), McpBindConfigDto.class);
                    componentConfig.setBindConfig(mcpBindConfigDto);
                }
                case Page -> {
                    PageBindConfigDto pageBindConfigDto = JSON.parseObject(componentConfig.getBindConfig().toString(), PageBindConfigDto.class);
                    componentConfig.setBindConfig(pageBindConfigDto);
                }
                case Event -> {
                    EventBindConfigDto eventBindConfigDto = JSON.parseObject(componentConfig.getBindConfig().toString(), EventBindConfigDto.class);
                    componentConfig.setBindConfig(eventBindConfigDto);
                }
                case Skill -> {
                    SkillBindConfigDto skillBindConfigDto = JSON.parseObject(componentConfig.getBindConfig().toString(), SkillBindConfigDto.class);
                    componentConfig.setBindConfig(skillBindConfigDto);
                }
                case SubAgent -> {
                    SubAgentBindConfigDto subAgentBindConfigDto = JSON.parseObject(componentConfig.getBindConfig().toString(), SubAgentBindConfigDto.class);
                    componentConfig.setBindConfig(subAgentBindConfigDto);
                }
            }
        } else if (!(componentConfig.getBindConfig() instanceof String)) {
            //处理一些脏数据导致异常的问题
            switch (componentConfig.getType()) {
                case Model -> {
                    if (!(componentConfig.getBindConfig() instanceof ModelBindConfigDto)) {
                        componentConfig.setBindConfig(new ModelBindConfigDto());
                    }
                }
                case Plugin -> {
                    if (!(componentConfig.getBindConfig() instanceof PluginBindConfigDto)) {
                        componentConfig.setBindConfig(new PluginBindConfigDto());
                    }
                }
                case Workflow -> {
                    if (!(componentConfig.getBindConfig() instanceof WorkflowBindConfigDto)) {
                        componentConfig.setBindConfig(new WorkflowBindConfigDto());
                    }
                }
                case Table -> {
                    if (!(componentConfig.getBindConfig() instanceof TableBindConfigDto)) {
                        componentConfig.setBindConfig(new TableBindConfigDto());
                    }
                }
                case Knowledge -> {
                    if (!(componentConfig.getBindConfig() instanceof KnowledgeBaseBindConfigDto)) {
                        componentConfig.setBindConfig(new KnowledgeBaseBindConfigDto());
                    }
                }
                case Trigger -> {
                    if (!(componentConfig.getBindConfig() instanceof TriggerConfigDto)) {
                        componentConfig.setBindConfig(new TriggerConfigDto());
                    }
                }
                case Variable -> {
                    if (!(componentConfig.getBindConfig() instanceof VariableConfigDto)) {
                        componentConfig.setBindConfig(new VariableConfigDto());
                    }
                }
                case Mcp -> {
                    if (!(componentConfig.getBindConfig() instanceof McpBindConfigDto)) {
                        componentConfig.setBindConfig(new McpBindConfigDto());
                    }
                }
                case Page -> {
                    if (!(componentConfig.getBindConfig() instanceof PageBindConfigDto)) {
                        componentConfig.setBindConfig(new PageBindConfigDto());
                    }
                }
                case Event -> {
                    if (!(componentConfig.getBindConfig() instanceof EventBindConfigDto)) {
                        componentConfig.setBindConfig(new EventBindConfigDto());
                    }
                }
                case Skill -> {
                    if (!(componentConfig.getBindConfig() instanceof SkillBindConfigDto)) {
                        componentConfig.setBindConfig(new SkillBindConfigDto());
                    }
                }
                case SubAgent -> {
                    if (!(componentConfig.getBindConfig() instanceof SubAgentBindConfigDto)) {
                        componentConfig.setBindConfig(new SubAgentBindConfigDto());
                    }
                }
            }
        }
    }
}
