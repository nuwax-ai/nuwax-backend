package com.xspaceagi.agent.web.ui.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HomeCategoryDto {

    private String name;

    private String icon;

    private String type;

    private HomeCategoryTypeEnum categoryType;

    private Integer sort;

    public enum HomeCategoryTypeEnum {

        // Agent collection
        AGENT_COLLECT,
        // Agent recommendation
        AGENT_RECOMMEND,

        PERSONAL_SPACE,

        TEAM_SPACE;
    }

    public static String getSpaceTypeName(Long id) {
        return "SPACE_" + id;
    }
}
