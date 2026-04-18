package com.xspaceagi.system.web.dto;

import com.xspaceagi.system.infra.dao.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Data
public class UserAddDto implements Serializable {

    @Schema(description = "用户唯一标识，可以记录三方账号系统唯一标识")
    private String uid;

    @Schema(description = "手机号码")
    private String phone;

    @Schema(description = "密码")
    private String password;

    @Schema(description = "姓名")
    private String userName;

    @Schema(description = "手机号码")
    private String nickName;

    @Schema(description = "头像")
    private String avatar;

    @Schema(description = "邮箱")
    private String email;

    @Schema(description = "角色")
    private User.Role role;
}
