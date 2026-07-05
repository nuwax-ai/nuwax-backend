package com.xspaceagi.agent.web.ui.controller.dto;

import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import lombok.Data;

import java.util.List;

@Data
public class ResourceGroupQueryDto {

    private Long spaceId;
    private List<Published.TargetType> types;
    private String name;
}
