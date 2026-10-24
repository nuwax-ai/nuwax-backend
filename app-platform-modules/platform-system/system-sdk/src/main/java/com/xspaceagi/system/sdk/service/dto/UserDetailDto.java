package com.xspaceagi.system.sdk.service.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Date;

@Data
public class UserDetailDto {

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

    /**
     * 用户状态。sdk 模块不能依赖 system-infra 的 {@code User.Status} 枚举，
     * 故以枚举名字符串下发：Enabled / Disabled / Deleted；由 BeanUtils 拷贝时
     * 类型不匹配会被跳过，需要转换方显式赋值（如 InternalApiController#toDetailDto）。
     */
    @Schema(description = "用户状态（枚举名）：Enabled/Disabled/Deleted")
    private String status;

    /** 用户角色，同 status 以枚举名字符串下发：Admin / User */
    @Schema(description = "用户角色（枚举名）：Admin/User")
    private String role;

    @Schema(description = "邮箱")
    private String email;

    @Schema(description = "手机号码")
    private String phone;

    @Schema(description = "最后登录时间")
    private Date lastLoginTime;

    @Schema(description = "创建时间")
    private Date created;

    @Schema(description = "更新时间")
    private Date modified;
}
