package com.xspaceagi.system.application.dto;

import com.xspaceagi.system.infra.dao.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Data
public class UserQueryDto implements Serializable {

    @Schema(description = "用户ID")
    private Long id;

    @Schema(description = "用户姓名")
    private String userName;

    @Schema(description = "昵称")
    private String nickName;

    @Schema(description = "邮箱")
    private String email;

    @Schema(description = "手机号码")
    private String phone;

    @Schema(description = "用户状态")
    private User.Status status;

    @Schema(description = "角色")
    private User.Role role;
}
