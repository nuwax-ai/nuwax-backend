package com.xspaceagi.custompage.ui.web.dto;

import java.io.Serializable;
import java.util.List;

import com.xspaceagi.agent.core.spec.enums.ModelApiProtocolEnum;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Model list response body")
public class CustomPageModelRes implements Serializable {

    @Schema(description = "Chat model list")
    private List<ModelDto> chatModelList;

    @Schema(description = "Multimodal model list")
    private List<ModelDto> multiModelList;

    @Schema(description = "Model metadata")
    @Data
    public static class ModelDto implements Serializable {
        @Schema(description = "Model ID")
        private Long id;

        @Schema(description = "Model display name")
        private String name;

        @Schema(description = "Model description")
        private String description;

        @Schema(description = "Model identifier")
        private String model;

        @Schema(description = "API protocol, e.g. OpenAI, Ollama")
        private ModelApiProtocolEnum apiProtocol;

        @Schema(description = "Tenant ID")
        private Long tenantId;

        @Schema(description = "Space ID")
        private Long spaceId;

        private Integer isReasonModel;

        @Schema(description = "Max tokens limit")
        private Integer maxTokens;

    }
}
