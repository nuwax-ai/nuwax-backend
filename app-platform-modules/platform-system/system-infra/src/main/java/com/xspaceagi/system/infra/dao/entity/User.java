package com.xspaceagi.system.infra.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("user")
public class User {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String uid;
    @TableField(value = "_tenant_id")
    private Long tenantId;
    private String userName;
    private String nickName;
    private String avatar;
    private Status status;
    private Role role;
    private String password;
    private Integer resetPass;
    private String email;
    private String phone;
    private Date lastLoginTime;
    private String lang;
    private Date created;
    private Date modified;

    // Enum for role column
    public enum Role {
        Admin, User
    }

    public enum Status {
        Enabled, Disabled, Deleted
    }
}