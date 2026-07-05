package com.xspaceagi.agent.core.adapter.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Hook规则条目")
public class HookEntryDto implements Serializable {

    // 过滤条件（如 "Bash" 仅针对 Bash 工具）
    @Schema(description = "匹配器，如工具名或正则表达式")
    private String matcher;

    @Schema(description = "Hook配置列表，每项为解析后的 JSON 对象")
    private List<Object> hooks;
}
