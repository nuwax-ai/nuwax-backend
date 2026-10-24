package com.xspaceagi.userapp.sdk.dto;

import java.io.Serializable;

import lombok.Data;

@Data
public class UserProjectCreateDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 创建人用户ID（必填） */
    private Long creatorId;

    /** 空间ID（必填） */
    private Long spaceId;

    /** 项目名称（可选） */
    private String name;

    /** 项目描述（可选） */
    private String description;

    /** 项目图标（可选） */
    private String icon;

    /** 沙箱ID（必填，-1 表示自动分配云端沙箱） */
    private Long sandboxId;

    /** 创建时绑定的工作目录（可选；仅个人沙箱，占用报错；空=按规则生成） */
    private String workspacePath;


}
