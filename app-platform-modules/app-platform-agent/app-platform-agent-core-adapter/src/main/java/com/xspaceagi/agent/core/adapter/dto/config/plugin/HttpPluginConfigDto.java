package com.xspaceagi.agent.core.adapter.dto.config.plugin;

import com.xspaceagi.agent.core.adapter.dto.config.workflow.HttpNodeConfigDto;
import com.xspaceagi.agent.core.spec.enums.HttpMethodEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class HttpPluginConfigDto extends PluginConfigDto {

    //请求方法
    @Schema(description = "请求方法", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "HTTP method is required")
    private HttpMethodEnum method;

    @Schema(description = "请求地址", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Request URL is required")
    private String url;

    //请求内容格式
    @Schema(description = "请求内容格式", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Request body format is required")
    private HttpNodeConfigDto.ContentTypeEnum contentType;

    //请求超时时间
    @Schema(description = "请求超时时间", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Request timeout is required")
    private Integer timeout;
}
