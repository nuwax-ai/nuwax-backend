package com.xspaceagi.ctl.dto;

import java.io.Serializable;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 内部调用接口「已发布智能体搜索」的请求体。
 * <p>
 * tenantId 不在本体内：它与其他内部接口保持一致，走 query 参数显式声明，
 * 用于覆盖请求上下文（见 InternalApiController#applyTenant）。
 */
@Data
public class InternalAgentSearchRequest implements Serializable {

    @Schema(description = "用户ID（必填）。决定可见范围：该用户所属空间 + 租户系统广场；"
            + "同时按该用户的数据权限过滤访问受控的智能体")
    private Long userId;

    @Schema(description = "为 true 时只返回系统推荐（官方标识）的智能体，忽略空间范围；默认 false")
    private Boolean official;

    @Schema(description = "限定单个空间ID（可选）。必须是 userId 所属的空间，否则返回空页")
    private Long spaceId;

    @Schema(description = "为 true 时只返回空间内发布的智能体（不含租户系统广场内容）；默认 false。"
            + "仅在不传 spaceId 时有意义")
    private Boolean onlySpace;

    @Schema(description = "关键字，匹配智能体名称与描述（可选）")
    private String kw;

    @Schema(description = "分类编码（可选），取值见广场分类接口返回的 code")
    private String category;

    @Schema(description = "页码，从 1 开始；默认 1")
    private Integer page;

    @Schema(description = "每页条数，默认 10，上限 100")
    private Integer pageSize;
}
