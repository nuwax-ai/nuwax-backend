package com.xspaceagi.system.web.dto;

import com.xspaceagi.system.infra.dao.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Date;

@Data
public class UserInfo {

    @Schema(description = "用户ID")
    private Long id;

    @Schema(description = "用户唯一标识")
    private String uid;

    @Schema(description = "商户ID")
    private Long tenantId;

    @Schema(description = "用户名")
    private String userName;

    @Schema(description = "用户昵称")
    private String nickName;

    @Schema(description = "用户头像")
    private String avatar;

    @Schema(description = "用户状态")
    private User.Status status;

    @Schema(description = "角色")
    private User.Role role;

    @Schema(description = "创建时间")
    private Date created;

    private String accountType;
    /**
     * 身份关联标识：account_type=agent 时为 agent id；=group 时为 channel:sessionKey。
     * <p>虚拟身份行必须填写，否则唯一键 uk_tenant_account_ref 对 NULL 不生效，并发下会重复创建主体。
     */
    private String principalRefId;
}
