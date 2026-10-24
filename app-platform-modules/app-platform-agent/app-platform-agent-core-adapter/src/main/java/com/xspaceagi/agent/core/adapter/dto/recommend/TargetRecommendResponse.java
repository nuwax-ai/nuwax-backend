package com.xspaceagi.agent.core.adapter.dto.recommend;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TargetRecommendResponse {

    private Long id;
    private Long tenantId;
    private String targetType;
    private Long targetId;
    private String recType;
    private String functionType;
    private String label;
    private String icon;
    private String placeholder;
    private String category;
    private List<RecommendPrompt> prompts;
    private Integer sort;
    private Date modified;
    private Date created;
}
