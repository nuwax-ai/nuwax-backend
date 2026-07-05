package com.xspaceagi.agent.core.adapter.dto;

import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.spec.enums.ComponentTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

@Data
public class ComponentDto implements Serializable {

    @Schema(description = "组件ID")
    private Long id;

    @Schema(description = "空间ID")
    private Long spaceId;

    @Schema(description = "分组ID")
    private Long groupId;

    @Schema(description = "组件类型")
    private ComponentTypeEnum type;

    @Schema(description = "组件名称")
    private String name;

    @Schema(description = "组件描述")
    private String description;

    @Schema(description = "图标地址")
    private String icon;

    @Schema(description = "发布状态，工作流、插件有效")
    private Published.PublishStatus publishStatus;

    @Schema(description = "最后编辑时间")
    private Date modified;

    @Schema(description = "创建时间")
    private Date created;

    private Long creatorId;

    @Schema(description = "创建者信息")
    private CreatorDto creator;

    @Schema(description = "扩展字段")
    private Object ext;

    @Schema(description = "权限列表")
    private List<String> permissions;

    @Schema(description = "是否启用")
    private Integer enabled;

    @Schema(description = "[插件专用]开发时使用的会话ID")
    private Long devAgentConversationId;

}
