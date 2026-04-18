package com.xspaceagi.system.spec.common;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * 用户信息,从拦截器里获取到的用户信息
 */
@Getter
@Setter
@Builder
public class UserContext {


    /**
     * 用户ID
     */
    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "用户唯一标识,部分租户会有值")
    private String uid;

    @Schema(description = "头像")
    private String avatar;
    /**
     * 用户名
     */
    @Schema(description = "用户名")
    private String userName;

    @Schema(description = "用户昵称")
    private String nickName;

    @Schema(description = "管理员邮箱,密文")
    private String email;
    /**
     * 手机号
     */
    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "用户状态，1:启用;-1:禁用")
    private Integer status;

    @Schema(description = "所属机构ID")
    private Long orgId;

    @Schema(description = "所属机构名称")
    private String orgName;

    @Schema(description = "角色类型，1:管理员角色;2:普通角色")
    private Integer roleType;

    @Schema(description = "所属租户ID")
    private Long tenantId;

    @Schema(description = "所属租户名称")
    private String tenantName;

    private Object tenantConfig;

    @Schema(description = "语言环境")
    private Map<String, String> langMap;
}
