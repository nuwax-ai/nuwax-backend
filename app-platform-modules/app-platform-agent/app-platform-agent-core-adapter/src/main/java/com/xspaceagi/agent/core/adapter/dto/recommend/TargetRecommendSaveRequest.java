package com.xspaceagi.agent.core.adapter.dto.recommend;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TargetRecommendSaveRequest {

    private Long id;

    @NotNull(message = "targetType不能为空")
    private String targetType;

    @NotNull(message = "targetId不能为空")
    private Long targetId;

    @NotNull(message = "recType不能为空")
    private String recType;

    @NotNull(message = "functionType不能为空")
    private String functionType;

    private String label;

    private String icon;

    private String placeholder;

    private Integer sort;
}
