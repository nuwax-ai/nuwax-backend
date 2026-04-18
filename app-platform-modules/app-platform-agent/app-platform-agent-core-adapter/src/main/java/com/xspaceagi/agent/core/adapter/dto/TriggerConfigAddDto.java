package com.xspaceagi.agent.core.adapter.dto;

import com.xspaceagi.agent.core.adapter.dto.config.TriggerConfigDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TriggerConfigAddDto extends TriggerConfigDto {

    @Schema(description = "agentId", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "agentId is required")
    private Long agentId;
}
