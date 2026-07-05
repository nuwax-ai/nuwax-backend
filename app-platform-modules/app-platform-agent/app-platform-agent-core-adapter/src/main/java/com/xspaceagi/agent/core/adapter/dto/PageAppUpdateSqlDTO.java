package com.xspaceagi.agent.core.adapter.dto;

import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
public class PageAppUpdateSqlDTO {

    @Schema(description = "项目ID，环境变量中读取 DEV_PROJECT_ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String projectId;

    @Schema(description = "API ID")
    private Long apiId;

    @Schema(description = "参数，使用一级即可")
    private List<Arg> args;

    @Schema(description = "SQL：Variable placeholders do not need single quotes, e.g., SELECT * FROM custom_table WHERE agent_id = {{agent_id}};For LIKE fuzzy queries, use $+variable, e.g., SELECT * FROM custom_table WHERE agent_name LIKE '%${{agent_name}}%';", requiredMode = Schema.RequiredMode.REQUIRED)
    private String sql;

    @Schema(description = "API名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String apiName;

    @Schema(description = "API描述", requiredMode = Schema.RequiredMode.REQUIRED)
    private String description;
}
