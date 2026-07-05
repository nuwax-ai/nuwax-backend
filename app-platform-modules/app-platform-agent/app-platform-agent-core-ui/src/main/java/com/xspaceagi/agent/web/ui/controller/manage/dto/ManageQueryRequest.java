package com.xspaceagi.agent.web.ui.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "管理查询请求")
public class ManageQueryRequest {

    @Schema(description = "页码，从1开始")
    private Integer pageNo = 1;

    @Schema(description = "每页大小")
    private Integer pageSize = 10;

    @Schema(description = "名称模糊搜索")
    private String name;

    @Schema(description = "创建人ID列表")
    private List<Long> creatorIds;

    @Schema(description = "创建人名称搜索")
    private String creatorName;

    @Schema(description = "空间ID")
    private Long spaceId;

    @Schema(description = "管控条件")
    private Integer accessControl;

    @Schema(description = "推荐类型：Home、Official、ChatBoxNav")
    private String recType;

    @Schema(description = "目标类型：Agent、PageApp、Skill、Plugin、Workflow")
    private String targetType;
}