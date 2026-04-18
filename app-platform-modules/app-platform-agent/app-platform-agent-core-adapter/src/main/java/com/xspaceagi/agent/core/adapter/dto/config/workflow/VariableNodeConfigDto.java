package com.xspaceagi.agent.core.adapter.dto.config.workflow;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class VariableNodeConfigDto extends NodeConfigDto {

    //配置类型枚举包括 设置变量值、获取变量值
    @Schema(description = "配置类型枚举包括 设置变量值、获取变量值", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Configuration type is required")
    private ConfigTypeEnum configType;

    public enum ConfigTypeEnum {
        SET_VARIABLE,
        GET_VARIABLE
    }

}
