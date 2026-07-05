package com.xspaceagi.agent.core.adapter.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "AI生成项目信息请求")
public class GenerateInfoReqDto implements Serializable {

    @Schema(description = "用户需求描述", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    private String prompt;
}
