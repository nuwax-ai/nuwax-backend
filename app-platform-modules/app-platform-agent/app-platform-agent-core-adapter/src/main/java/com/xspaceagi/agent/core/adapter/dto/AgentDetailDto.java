package com.xspaceagi.agent.core.adapter.dto;

import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import com.xspaceagi.agent.core.adapter.dto.config.bind.EventBindConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.AgentConfig;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

@Data
public class AgentDetailDto implements Serializable {

    private Long spaceId;

    private Long creatorId;

    @Schema(description = "智能体ID")
    private Long agentId;

    @Schema(description = "智能体类型，ChatBot 对话智能体；PageApp 网页应用智能体")
    private String type;

    @Schema(description = "智能体名称")
    private String name;

    @Schema(description = "智能体介绍信息")
    private String description;

    @Schema(description = "图标")
    private String icon;

    @Schema(description = "发布备注信息")
    private String remark;

    @Schema(description = "智能体发布时间")
    private Date publishDate;

    @Schema(description = "统计信息")
    private StatisticsDto statistics;

    @Schema(description = "发布者信息")
    private PublishUserDto publishUser;

    @Schema(description = "当前登录用户是否收藏")
    private boolean isCollect;

    @Schema(description = "智能体分类名称")
    private String category;

    @Schema(description = "是否开启问题建议")
    private AgentConfig.OpenStatus openSuggest;
    @Schema(description = "开场白文案")
    private String openingChatMsg;

    @Schema(description = "引导问题（弃用）")
    private List<String> openingGuidQuestions;

    @Schema(description = "引导问题")
    private List<GuidQuestionDto> guidQuestionDtos;

    @Schema(description = "是否开启定时任务")
    private AgentConfig.OpenStatus openScheduledTask;

    @Schema(description = "用户手动填写的变量参数")
    private List<Arg> variables;

    @Schema(description = "分享链接")
    private String shareLink;

    @Schema(description = "可手动选择的组件列表")
    private List<AgentManualComponentDto> manualComponents;

    @Schema(description = "是否允许复制, 1 允许")
    private Integer allowCopy;

    @Schema(description = "会话ID")
    private Long conversationId;

    @Schema(description = "沙盒ID")
    private Long sandboxId;

    @Schema(description = "是否拥有权限")
    private boolean hasPermission;

    @Schema(description = "是否默认展开扩展页面区域, 1 展开；0 不展开")
    private Integer expandPageArea;

    @Schema(description = "是否隐藏聊天区域，1 隐藏；0 不隐藏")
    private Integer hideChatArea;

    @Schema(description = "是否隐藏远程桌面, 1 隐藏；0 不隐藏")
    private Integer hideDesktop;

    @Schema(description = "是否允许用户在对话框中选择其他模型, 1 允许，其他不允许")
    private Integer allowOtherModel;

    @Schema(description = "是否允许用户在对话框中@技能， 1 允许，其他不允许")
    private Integer allowAtSkill;

    @Schema(description = "是否允许用户在对话框中选择自己的电脑， 1 允许，其他不允许")
    private Integer allowPrivateSandbox;

    @Schema(description = "扩展页面首页")
    private String pageHomeIndex;

    @Schema(description = "事件绑定配置")
    private EventBindConfigDto eventBindConfig;
}
