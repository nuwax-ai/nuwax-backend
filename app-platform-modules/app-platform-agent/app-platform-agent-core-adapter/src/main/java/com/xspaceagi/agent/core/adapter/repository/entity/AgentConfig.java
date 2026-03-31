package com.xspaceagi.agent.core.adapter.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@TableName("agent_config")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AgentConfig {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id; // 智能体ID

    private String uid; // agent唯一标识

    private String type;

    @TableField(value = "_tenant_id")
    private Long tenantId; // 商户ID

    private Long spaceId; // 空间ID

    private Long creatorId; // 创建者ID

    private String name; // Agent名称

    private String description; // Agent描述

    private String icon; // 图标地址

    private String systemPrompt; // 系统提示词

    private String userPrompt; // 用户消息提示词，{{AGENT_USER_MSG}}引用用户消息

    private OpenStatus openSuggest; // 是否开启问题建议

    private String suggestPrompt; // 用户问题建议

    private String openingChatMsg; // 首次打开聊天框自动回复消息

    private String openingGuidQuestion; // 开场引导问题

    private OpenStatus openLongMemory; // 是否开启长期记忆

    private Published.PublishStatus publishStatus; // Agent发布状态

    private Integer yn; // 逻辑删除，1为删除

    private Date modified; // 更新时间

    private Date created; // 创建时间

    private Long devConversationId; // 开发模式对应的会话ID

    private AgentConfig.OpenStatus openScheduledTask;

    private Integer expandPageArea;//是否默认展开扩展页面区域, 1 展开；0 不展开

    private Integer hideChatArea;//是否隐藏聊天区域，1 隐藏；0 不隐藏

    private String extra;

    private Integer accessControl;

    private Integer hideDesktop;

    private Integer allowOtherModel;

    private Integer allowAtSkill;

    private Integer allowPrivateSandbox;

    public enum OpenStatus {
        Open, Close;
    }
}
