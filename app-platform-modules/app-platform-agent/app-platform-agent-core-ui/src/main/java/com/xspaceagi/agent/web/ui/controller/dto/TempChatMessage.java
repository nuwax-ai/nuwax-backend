package com.xspaceagi.agent.web.ui.controller.dto;

import com.xspaceagi.agent.core.adapter.dto.AttachmentDto;
import com.xspaceagi.agent.core.adapter.dto.TryReqDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class TempChatMessage {

    @Schema(description = "链接Key", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "chatKey is required")
    private String chatKey;

    @Schema(description = "会话唯一标识")
    @NotNull(message = "conversationUid is required")
    private String conversationUid;

    @Schema(description = "变量参数，前端需要根据agent配置组装参数")
    private Map<String, Object> variableParams;

    @Schema(description = "chat消息")
    private String message;

    @Schema(description = "附件列表")
    private List<AttachmentDto> attachments;

    private List<TryReqDto.SelectedComponentDto> selectedComponents;
}
