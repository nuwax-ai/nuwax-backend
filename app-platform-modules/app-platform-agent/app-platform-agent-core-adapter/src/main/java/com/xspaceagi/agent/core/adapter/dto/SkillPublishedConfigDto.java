package com.xspaceagi.agent.core.adapter.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
public class SkillPublishedConfigDto {

    @Schema(description = "配置格式标记")
    private String format;

    private Long id;

    private String name;

    private String description;

    private String icon;

    private List<SkillFileDto> files;

    @Schema(description = "技能发布zip代理下载地址")
    private String zipFileUrl;

    private Long devAgentConversationId;
}
