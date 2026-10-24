package com.xspaceagi.userapp.sdk.dto;

import java.io.Serializable;
import com.xspaceagi.system.spec.enums.ProjectTypeEnum;

import lombok.Data;

/**
 * 登记/同步用户项目参数
 */
@Data
public class UserProjectUpsertDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private ProjectTypeEnum projectType;

    private Long refId;

    /** 空间ID（必填） */
    private Long spaceId;

    /** 创建人用户ID（必填） */
    private Long creatorId;

    private String name;

    private String description;

    private String icon;

    private Long sandboxId;

    /** 开发关联智能体ID（仅全栈应用登记写入） */
    private Long devAgentId;

    /** 名称是否已定制 */
    private Boolean nameDefined;
}
