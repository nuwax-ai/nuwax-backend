package com.xspaceagi.ctl.dto;

import java.io.Serializable;
import java.util.Date;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 内部调用接口「智能体会话」条目。
 * <p>
 * 只暴露定位与展示所需字段：会话消息、变量、智能体明细等重内容由调用方按需另取，
 * 不在本契约内。
 */
@Data
public class InternalConversationDto implements Serializable {

    @Schema(description = "会话ID（后续「发起会话」接口的 conversationId）")
    private Long conversationId;

    @Schema(description = "会话唯一标识 uid")
    private String uid;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "会话归属用户ID")
    private Long userId;

    @Schema(description = "智能体ID")
    private Long agentId;

    @Schema(description = "会话主题")
    private String topic;

    @Schema(description = "会话类型：Normal / TempChat / Development / DevDebug / TASK 等")
    private String type;

    @Schema(description = "是否开发模式：1 是，0 否")
    private Integer devMode;

    @Schema(description = "沙箱服务器ID")
    private String sandboxServerId;

    @Schema(description = "文件访问目录（项目绑定时下发）")
    private String fileWorkspacePath;

    @Schema(description = "智能体侧工作目录（项目绑定时下发）")
    private String agentWorkspacePath;

    @Schema(description = "创建时间")
    private Date created;

    @Schema(description = "更新时间")
    private Date modified;
}
