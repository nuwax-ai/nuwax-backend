package com.xspaceagi.system.application.dto;

import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.spec.common.UserContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Date;
import java.util.Map;
import java.util.Objects;

@Schema(description = "用户信息")
@Data
public class UserDto {

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

    @Schema(description = "用户密码")
    private String password;

    @Schema(description = "判断用户是否设置过密码，如果未设置过，需要弹出密码设置框让用户设置密码")
    private Integer resetPass;

    @Schema(description = "用户状态")
    private User.Status status;

    @Schema(description = "角色")
    private User.Role role;

    @Schema(description = "邮箱")
    private String email;

    @Schema(description = "手机号码")
    private String phone;

    @Schema(description = "最后登录时间")
    private Date lastLoginTime;

    @Schema(description = "最近的语言环境")
    private String lang;

    @Schema(description = "创建时间")
    private Date created;

    @Schema(description = "更新时间")
    private Date modified;

    @Schema(description = "语言环境对应的值，仅类型为System")
    private Map<String, String> langMap;

    /**
     * 转换为对象 UserContext,用于上下文
     */
    public static UserContext convertToUserContext(UserDto userDto) {
        if (Objects.isNull(userDto)) {
            return null;
        }

        int status = 1;
        if (userDto.getStatus() != User.Status.Enabled) {
            status = -1;
        }


        return UserContext.builder()
                .userId(userDto.getId())
                .uid(userDto.getUid())
                .userName(userDto.getUserName())
                .nickName(userDto.getNickName())
                .email(userDto.getEmail())
                .phone(userDto.getPhone())
                .status(status)
                .orgId(userDto.getTenantId())
                .orgName(null)
                .roleType(null)
                .langMap(userDto.getLangMap())
                .build();

    }

}