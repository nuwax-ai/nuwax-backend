package com.xspaceagi.system.web.dto;

import com.xspaceagi.system.infra.dao.entity.SpaceUser;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

@Data
public class SpaceUserQueryDto implements Serializable {

    @Schema(description = "空间ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Space ID is required")
    private Long spaceId;

    @Schema(description = "关键字", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String kw;

    @Schema(description = "角色", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private SpaceUser.Role role;
}
