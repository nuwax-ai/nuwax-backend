package com.xspaceagi.agent.web.ui.controller.dto;

import com.xspaceagi.agent.core.adapter.dto.PublishUserDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Date;

@Data
public class PublishItemDto {

    @Schema(description = "发布ID，审核中无该ID，审核中下架按钮禁用")
    private Long publishId;

    @Schema(description = "发布状态")
    private Published.PublishStatus publishStatus;

    @Schema(description = "发布范围,Tenant 系统广场；Space空间广场")
    private Published.PublishScope scope;

    @Schema(description = "空间ID,scope为空间广场时有效")
    private Long spaceId;

    @Schema(description = "是否允许复制,0不允许，1允许")
    private Integer allowCopy;

    @Schema(description = "是否只展示模板,0否，1是")
    private Integer onlyTemplate;

    @Schema(description = "发布时间")
    private Date publishDate;

    @Schema(description = "描述信息")
    private String description;

    @Schema(description = "发布者信息")
    private PublishUserDto publishUser;

    @Schema(description = "分类")
    private String category;
}
