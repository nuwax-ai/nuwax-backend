package com.xspaceagi.agent.core.adapter.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xspaceagi.agent.core.spec.handler.JsonTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.Map;

@Data
@TableName(value = "conversation", autoResultMap = true)
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Conversation {
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("_tenant_id")
    private Long tenantId;

    private String uid;

    private Long userId;

    private Long agentId;

    private String topic;

    private String summary;

    private String icon;

    @TableField(value = "variables", typeHandler = JsonTypeHandler.class)
    private Map<String, Object> variables;

    private Integer devMode;

    private Integer topicUpdated;

    private ConversationType type;

    private String taskId;

    private ConversationTaskStatus taskStatus;

    private String taskCron;

    private String sandboxServerId;

    private String sandboxSessionId;
    private Long devSpaceId;
    private String devTargetType;
    private String devTargetId;

    private Date modified;

    private Date created;

    public enum ConversationType {
        Chat,
        TempChat,
        TASK,
        TaskCenter,
        Development,
        DevDebug
    }

    public enum ConversationTaskStatus {
        CREATE,
        EXECUTING,
        CANCEL,
        COMPLETE,
        FAILED
    }
}
