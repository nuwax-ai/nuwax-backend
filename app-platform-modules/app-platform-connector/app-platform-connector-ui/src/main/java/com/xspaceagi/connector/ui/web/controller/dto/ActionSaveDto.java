package com.xspaceagi.connector.ui.web.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 创建/编辑 action 请求体（用户侧仅 DECLARATIVE）。
 * <p>
 * 长度上限与 connector_action 列宽对应（action_key 128 / name 128 /
 * exec_type 16 / exec_ref 255）；description 列为 text，取 16000 字符的
 * 保守上限（utf8mb4 下 TEXT 上限 65535 字节）避免超长直接打崩 DB。
 * <p>
 * 本 DTO 同时服务创建与编辑，字段一律可空：编辑是「部分更新」，未传的字段沿用旧值，
 * 因此这里只做长度约束；必填由服务层按场景判定（新建才要求 actionKey，编辑时取自 path）。
 */
@Data
@Schema(description = "创建/编辑 action 请求")
public class ActionSaveDto {

    @Size(max = 128, message = "长度不能超过 128")
    @Schema(description = "创建时必填；编辑时以 URL 中的 actionKey 为准")
    private String actionKey;

    @Size(max = 128, message = "长度不能超过 128")
    private String name;

    @Size(max = 16000, message = "长度不能超过 16000")
    private String description;

    private List<String> tags;

    private Object inputArgs;

    private Object outputArgs;

    @Size(max = 16, message = "长度不能超过 16")
    @Schema(description = "DECLARATIVE/CUSTOM/PLUGIN/WORKFLOW")
    private String execType;

    @Size(max = 255, message = "长度不能超过 255")
    @Schema(description = "绑定型时填插件/工作流 id")
    private String execRef;

    @Schema(description = "{\"method\":\"POST\",\"path\":\"/repos/{owner}/{repo}/issues\",\"path\":{\"owner\":\"input.owner\"},\"query\":{},\"body\":{},\"response\":{\"extract\":\"$\"}}")
    private Object httpSpec;
}
