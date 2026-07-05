package com.xspaceagi.agent.web.ui.dto;

import com.xspaceagi.agent.core.adapter.dto.SkillFileDto;
import com.xspaceagi.agent.core.adapter.dto.SkillExtDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.spec.enums.UsageScenarioEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

@Data
@Schema(description = "技能响应DTO")
public class SkillDto implements Serializable {

    @Schema(description = "技能ID")
    private Long id;

    @Schema(description = "技能名称")
    private String name;

    @Schema(description = "技能描述")
    private String description;

    @Schema(description = "技能图标")
    private String icon;

    @Schema(description = "文件内容")
    private List<SkillFileDto> files;

    @Schema(description = "扩展字段，例如 supportTaskAgent/supportPageApp")
    private SkillExtDto ext;

    @Schema(description = "适用场景列表，如 [TaskAgent, PageApp]")
    private List<UsageScenarioEnum> usageScenarios;

    @Schema(description = "发布状态")
    private Published.PublishStatus publishStatus;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "空间ID")
    private Long spaceId;

    @Schema(description = "创建时间")
    private Date created;

    @Schema(description = "创建人ID")
    private Long creatorId;

    @Schema(description = "创建人")
    private String creatorName;

    @Schema(description = "更新时间")
    private Date modified;

    @Schema(description = "最后修改人ID")
    private Long modifiedId;

    @Schema(description = "最后修改人")
    private String modifiedName;

    @Schema(description = "已发布的范围，用于发布时做默认选中")
    private Published.PublishScope scope;

    @Schema(description = "发布时间，如果不为空，与当前modified时间做对比，如果发布时间小于modified，则前端显示：有更新未发布")
    private Date publishDate;

    @Schema(description = "已发布的空间ID", hidden = true)
    private List<Long> publishedSpaceIds;

    @Schema(description = "已发布的分类")
    private String category;

    @Schema(description = "权限列表")
    private List<String> permissions;

    @Schema(description = "开发时使用的会话ID")
    private Long devAgentConversationId;
}