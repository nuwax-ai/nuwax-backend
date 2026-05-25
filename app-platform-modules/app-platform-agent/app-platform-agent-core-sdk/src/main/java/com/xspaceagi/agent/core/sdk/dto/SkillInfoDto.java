package com.xspaceagi.agent.core.sdk.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class SkillInfoDto implements Serializable {
    private Long id;
    private Long spaceId;
    private Long creatorId;
    private String name;
    private String description;
    private String icon;
}
