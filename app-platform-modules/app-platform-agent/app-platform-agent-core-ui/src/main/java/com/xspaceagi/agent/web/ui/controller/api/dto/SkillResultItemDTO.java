package com.xspaceagi.agent.web.ui.controller.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SkillResultItemDTO {
    private Long id;
    private String name;
    private String description;
    private String downloadUrl;
}
