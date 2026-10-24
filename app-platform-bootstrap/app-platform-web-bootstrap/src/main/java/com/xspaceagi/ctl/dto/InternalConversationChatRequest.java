package com.xspaceagi.ctl.dto;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

import com.xspaceagi.agent.core.adapter.dto.AttachmentDto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 内部调用接口「发起智能体会话」的请求体（SSE 流式响应）。
 * <p>
 * tenantId 不在本体内：与其他内部接口一致走 query 参数显式声明。
 * 会话必须已存在（由「创建智能体会话」接口得到 conversationId），本接口只负责发起一轮执行。
 */
@Data
public class InternalConversationChatRequest implements Serializable {

    @Schema(description = "会话归属用户ID（必填）。须属于调用方声明的租户，且是 conversationId 的归属用户")
    private Long userId;

    @Schema(description = "实际付费方用户ID（可选）。须属于调用方声明的租户。"
            + "仅在费用原本由使用者承担时生效：若该用户处于包干订阅或试用（费用判给智能体创建者），本字段不生效")
    private Long billUserId;

    @Schema(description = "会话ID（必填），来自「创建智能体会话」接口返回的 conversationId")
    private Long conversationId;

    @Schema(description = "本轮用户消息内容（必填）")
    private String message;

    @Schema(description = "变量参数（可选），按智能体配置组装")
    private Map<String, Object> variableParams;

    @Schema(description = "附件列表（可选）")
    private List<AttachmentDto> attachments;

    @Schema(description = "用户选择的模型ID（可选）")
    private Long modelId;

    @Schema(description = "前端选中的技能ID列表（可选）")
    private List<Long> skillIds;
}
