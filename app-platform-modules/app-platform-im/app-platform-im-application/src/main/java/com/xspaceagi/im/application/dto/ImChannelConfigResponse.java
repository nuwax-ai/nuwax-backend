package com.xspaceagi.im.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * IM 渠道配置响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "IM 渠道配置响应")
public class ImChannelConfigResponse {

    @Schema(description = "主键ID")
    private Long id;

    @Schema(description = "渠道类型：feishu/dingtalk/wework")
    private String channel;

    @Schema(description = "目标类型：bot/app")
    private String targetType;

    @Schema(description = "目标唯一标识")
    private String targetId;

    @Schema(description = "关联智能体ID")
    private Long agentId;

    @Schema(description = "关联智能体名称")
    private String agentName;

    @Schema(description = "关联智能体图标")
    private String agentIcon;

    @Schema(description = "关联智能体描述")
    private String agentDescription;

    @Schema(description = "是否启用")
    private Boolean enabled;

    @Schema(description = "渠道专有配置（JSON 字符串）")
    private String configData;

    @Schema(description = "输出方式：stream(流式输出)/once(一次性输出)")
    private String outputMode;

    @Schema(description = "配置名称")
    private String name;

    @Schema(description = "空间ID")
    private Long spaceId;

    @Schema(description = "创建时间")
    private Date created;

    @Schema(description = "创建者ID")
    private Long creatorId;

    @Schema(description = "创建者名称")
    private String creatorName;

    @Schema(description = "修改时间")
    private Date modified;

    @Schema(description = "修改者ID")
    private Long modifiedId;

    @Schema(description = "修改者名称")
    private String modifiedName;
}
