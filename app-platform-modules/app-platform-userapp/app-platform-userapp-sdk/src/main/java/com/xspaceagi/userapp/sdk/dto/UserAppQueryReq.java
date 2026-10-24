package com.xspaceagi.userapp.sdk.dto;

import java.io.Serializable;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "用户自定义应用查询条件")
public class UserAppQueryReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "空间ID")
    private Long spaceId;

    @Schema(description = "创建人用户ID")
    private Long creatorId;

    @Schema(description = "发布状态：Developing / Published")
    private UserAppPublishStatus publishStatus;

    @Schema(description = "应用名称（模糊匹配）")
    private String name;
}
