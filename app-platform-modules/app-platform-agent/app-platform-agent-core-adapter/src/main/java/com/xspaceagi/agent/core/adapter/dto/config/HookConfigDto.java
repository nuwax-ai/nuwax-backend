package com.xspaceagi.agent.core.adapter.dto.config;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class HookConfigDto implements Serializable {

    @Schema(description = "Hook列表")
    private List<Hook> hooks;

    @Data
    public static class Hook {
        @Schema(description = "Hook名称")
        private String name;
        @Schema(description = "Hook事件")
        private String event;
        @Schema(description = "Hook匹配规则")
        private String matcher;
        @Schema(description = "Hook类型")
        private String type;
        @Schema(description = "Hook配置")
        private String config;
        @Schema(description = "Hook状态,1 启用；0 停用")
        private Integer status;
    }
}
