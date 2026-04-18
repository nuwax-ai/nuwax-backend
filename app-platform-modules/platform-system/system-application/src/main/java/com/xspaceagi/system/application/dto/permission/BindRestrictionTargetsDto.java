package com.xspaceagi.system.application.dto.permission;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 绑定限制访问对象请求
 */
@Data
public class BindRestrictionTargetsDto implements Serializable {

    @NotNull(message = "subjectId is required")
    @Schema(description = "主体ID（如模型ID、智能体ID、网页应用智能体ID）")
    private Long subjectId;

    @Schema(description = "可访问的角色ID列表")
    private List<Long> roleIds = new ArrayList<>();

    @Schema(description = "可访问的用户组ID列表")
    private List<Long> groupIds = new ArrayList<>();
}
