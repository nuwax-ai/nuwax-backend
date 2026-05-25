package com.xspaceagi.agent.core.sdk.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ModelInfoDto implements Serializable {
    private Long spaceId;
    private Long id;
    private Long creatorId;
    private String name;
    private String description;
    private String icon;
    private String provider;
    private boolean isTenantModel;
}
