package com.xspaceagi.system.web.dto;

import com.xspaceagi.system.infra.dao.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Data
public class UserUpdateDto implements Serializable {

    @Schema(description = "用户名")
    private String userName;

    @Schema(description = "用户昵称")
    private String nickName;

    @Schema(description = "用户头像地址")
    private String avatar;

    @Schema(description = "角色")
    private User.Role role;

    @Schema(description = "管理员邮箱")
    private String email;

    @Schema(description = "手机号码")
    private String phone;

    @Schema(description = "密码")
    private String password;
}
