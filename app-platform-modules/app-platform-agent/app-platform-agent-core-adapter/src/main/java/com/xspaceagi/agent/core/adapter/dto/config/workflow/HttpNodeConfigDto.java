package com.xspaceagi.agent.core.adapter.dto.config.workflow;

import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import com.xspaceagi.agent.core.spec.enums.HttpMethodEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class HttpNodeConfigDto extends NodeConfigDto {

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
    private ContentTypeEnum contentType;

    //请求超时时间
    @Schema(description = "请求超时时间", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Request timeout is required")
    private Integer timeout;

    @Schema(description = "请求头")
    private List<Arg> headers;

    @Schema(description = "请求query参数")
    private List<Arg> queries;

    @Schema(description = "请求体")
    private List<Arg> body;

    public enum ContentTypeEnum {
        JSON,
        FORM_DATA,
        X_WWW_FORM_URLENCODED,
        OTHER
    }
}
