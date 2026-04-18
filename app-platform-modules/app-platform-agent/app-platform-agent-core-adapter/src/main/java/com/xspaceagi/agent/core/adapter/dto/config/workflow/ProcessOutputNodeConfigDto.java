package com.xspaceagi.agent.core.adapter.dto.config.workflow;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 插件节点配置
 */
@Getter
@Setter
public class ProcessOutputNodeConfigDto extends NodeConfigDto {

    @Schema(description = "返回类型", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Return type is required")
    private ReturnType  returnType;

    @Schema(description = "回答内容，返回类型为文本时，必须输入")
    private String content;

    public enum ReturnType {
        //变量
        VARIABLE,
        //文本
        TEXT
    }
}
