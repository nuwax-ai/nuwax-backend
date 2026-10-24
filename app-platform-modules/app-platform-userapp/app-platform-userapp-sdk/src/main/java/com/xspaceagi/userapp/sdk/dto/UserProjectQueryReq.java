package com.xspaceagi.userapp.sdk.dto;

import java.io.Serializable;
import java.util.List;

import com.xspaceagi.system.spec.enums.ProjectTypeEnum;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "用户项目查询条件")
public class UserProjectQueryReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "空间ID")
    private Long spaceId;

    @Schema(description = "创建人用户ID")
    private Long creatorId;

    @Schema(description = "项目类型（支持传多个；不传默认常规项目+全栈应用+三方应用）")
    private List<ProjectTypeEnum> projectTypes;

    @Schema(description = "项目名称（模糊匹配）")
    private String name;

    @Schema(description = "收藏过滤：all=全部（默认）；only=仅含收藏。值大小写不敏感")
    private String collectedFilter;

    @Schema(description = "归档过滤：all=全部；exclude=排除归档（默认）；only=仅归档。值大小写不敏感")
    private String archivedFilter;
}
