package com.xspaceagi.userapp.sdk.dto;

import java.io.Serializable;

import lombok.Data;

/**
 * 创建用户自定义应用项目参数。
 */
@Data
public class UserAppCreateDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 创建人用户ID（必填） */
    private Long creatorId;

    /** 空间ID（必填） */
    private Long spaceId;

    /** 应用名称（必填） */
    private String name;

    /** 应用描述（可选） */
    private String description;

    /** 应用图标（可选） */
    private String icon;

    /** 沙箱ID */
    private Long sandboxId;

    /** 创建时绑定的工作目录（可选；仅个人沙箱，占用报错；空=按规则生成） */
    private String workspacePath;

    /** 开发关联智能体ID（创建时解析写入） */
    private Long devAgentId;

}
