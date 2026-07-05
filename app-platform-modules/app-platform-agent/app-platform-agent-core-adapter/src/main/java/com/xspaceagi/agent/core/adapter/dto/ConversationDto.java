package com.xspaceagi.agent.core.adapter.dto;

import com.xspaceagi.agent.core.adapter.repository.entity.Conversation;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ConversationDto {

    @Schema(description = "会话ID")
    private Long id;

    private Long tenantId;

    private Long userId;

    @Schema(description = "会话UUID")
    private String uid;

    @Schema(description = "智能体ID")
    private Long agentId;

    @Schema(description = "会话主题")
    private String topic;

    @Schema(description = "会话摘要，当开启长期记忆时，会对每次会话进行总结")
    private String summary;

    @Schema(description = "会话图标")
    private String icon;

    @Schema(description = "用户填写的会话变量内容")
    private Map<String, Object> variables;

    private Date modified;

    private Date created;

    @Schema(description = "Agent信息，已发布过的agent才有此信息")
    private AgentDetailDto agent;

    @Schema(description = "会话消息列表，会话列表查询时不会返回该字段值")
    private List<ChatMessageDto> messageList;

    private Conversation.ConversationType type;

    private String taskId;

    @Schema(description = "任务状态，只针对 EXECUTING（执行中） 做展示")
    private Conversation.ConversationTaskStatus taskStatus;

    private String taskCron;

    private String taskCronDesc;

    private Integer devMode;

    private Integer topicUpdated;

    private String sandboxServerId;

    private String sandboxSessionId;

    @Schema(description = "开发项目所在的空间ID")
    private Long devSpaceId;
    @Schema(description = "开发模式目标类型 Agent,PageApp,Skill,Plugin")
    private String devTargetType;
    @Schema(description = "开发模式目标ID")
    private String devTargetId;

    @Schema(description = "已分享的URI地址，比对上了则不需要认证")
    private List<String> sharedUris;

    public Integer getDevMode() {
        return devMode == null ? 0 : devMode;
    }
}
