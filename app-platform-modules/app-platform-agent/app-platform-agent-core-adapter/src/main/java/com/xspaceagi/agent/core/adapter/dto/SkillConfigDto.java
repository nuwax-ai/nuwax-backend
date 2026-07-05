package com.xspaceagi.agent.core.adapter.dto;

import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

@Data
public class SkillConfigDto implements Serializable {

    private Long id; // 主键ID

    private String name; // 技能名称

    private String enName; // 技能英文名称

    private String description; // 技能描述

    private String icon; // 技能图标

    private List<SkillFileDto> files; // 文件内容

    @Schema(description = "扩展字段，例如 supportTaskAgent/supportPageApp")
    private SkillExtDto ext;

    private Published.PublishStatus publishStatus; // 发布状态

    private Long spaceId; // 空间ID

    private Date created; // 创建时间

    private Long creatorId; // 创建人ID

    private String creatorName; // 创建人

    private Date modified; // 更新时间

    private Long modifiedId; // 最后修改人ID

    private String modifiedName; // 最后修改人

    @Schema(description = "已发布的范围，用于发布时做默认选中")
    private Published.PublishScope scope;

    @Schema(description = "发布时间，如果不为空，与当前modified时间做对比，如果发布时间小于modified，则前端显示：有更新未发布")
    private Date publishDate;

    @Schema(description = "已发布的空间ID", hidden = true)
    private List<Long> publishedSpaceIds;

    @Schema(description = "已发布的分类")
    private String category;

    @Schema(description = "技能发布zip代理下载地址")
    private String zipFileUrl;

    @Schema(description = "技能开发智能体的关联的会话ID")
    private Long devAgentConversationId;
}