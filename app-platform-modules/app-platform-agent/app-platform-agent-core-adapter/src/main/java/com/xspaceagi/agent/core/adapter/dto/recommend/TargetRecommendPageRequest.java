package com.xspaceagi.agent.core.adapter.dto.recommend;

import lombok.Data;

@Data
public class TargetRecommendPageRequest {

    private Integer pageNo = 1;

    private Integer pageSize = 10;

    private String recType;

    private String targetType;
}
