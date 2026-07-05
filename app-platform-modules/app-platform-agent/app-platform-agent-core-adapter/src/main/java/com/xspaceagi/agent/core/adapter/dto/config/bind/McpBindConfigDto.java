package com.xspaceagi.agent.core.adapter.dto.config.bind;

import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class McpBindConfigDto implements Serializable {

    @Schema(description = "MCP工具名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String toolName;

    @Schema(description = "入参绑定配置，插件、工作流有效")
    private List<Arg> inputArgBindConfigs;

    @Schema(description = "参数绑定信息")
    private List<Arg> outputArgBindConfigs;

    @Schema(description = "卡片绑定信息")
    private CardBindConfigDto cardBindConfig;

    @Schema(description = "调用方式,自动调用、按需调用、手动选择(MANUAL)、手动选择和按需调用(MANUAL_ON_DEMAND)")
    private McpInvokeTypeEnum invokeType;

    @Schema(description = "是否异步执行，0-否，1-是")
    private Integer async;

    @Schema(description = "异步执行时回复内容")
    private String asyncReplyContent;

    @Schema(description = "是否默认选中，0-否，1-是")
    private Integer defaultSelected;

    @Schema(description = "是否需要审批，0-否，1-是")
    private Integer callApproval;

    public enum McpInvokeTypeEnum {
        //自动调用、按需调用、手动选择、手动选择和按需调用
        AUTO, ON_DEMAND, MANUAL, MANUAL_ON_DEMAND
    }
}
