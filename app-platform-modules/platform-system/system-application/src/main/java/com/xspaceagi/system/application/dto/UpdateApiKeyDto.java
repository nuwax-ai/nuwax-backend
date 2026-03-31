package com.xspaceagi.system.application.dto;

import com.xspaceagi.system.sdk.service.dto.UserAccessKeyDto;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Date;
import java.util.List;

@Data
public class UpdateApiKeyDto {

    @Schema(description = "用户ID", hidden = true)
    private Long userId;
    @Schema(description = "API Key")
    private String accessKey;
    @Schema(description = "状态 0 停用; 1 启用")
    private Integer status;
    @Schema(description = "名称")
    private String name;
    @Schema(description = "过期时间")
    private Date expire;
    @Schema(description = "接口调用频率限制，每分钟调用次数")
    private List<UserAccessKeyDto.ApiConfig> apiConfigs;
}
