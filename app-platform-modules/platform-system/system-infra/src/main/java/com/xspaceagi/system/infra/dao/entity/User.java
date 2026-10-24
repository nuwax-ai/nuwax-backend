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

    /** 账号类型：自然人。唯一可登录、且唯一出现在用户列表/搜索/统计口径里的类型。 */
    public static final String ACCOUNT_TYPE_USER = "user";
    /** 账号类型：智能体虚拟身份。承载智能体自身的记忆与配置，不代表任何真人。 */
    public static final String ACCOUNT_TYPE_AGENT = "agent";
    /** 账号类型：IM 群代理虚拟身份。群聊场景下替代配置机器人的真人，避免读到他/她的个人数据。 */
    public static final String ACCOUNT_TYPE_GROUP = "group";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String uid;
    @TableField(value = "_tenant_id")
    private Long tenantId;
    /**
     * 账号类型，取值见 ACCOUNT_TYPE_* 常量。存量行为 user。
     * <p>非 user 的行为虚拟身份，不应出现在任何「用户」语义的列表、搜索、统计与通知广播里。
     */
    private String accountType;
    /**
     * 身份关联标识：account_type=agent 时为 agent id；=group 时为 channel:sessionKey。
     * <p>虚拟身份行必须填写，否则唯一键 uk_tenant_account_ref 对 NULL 不生效，并发下会重复创建主体。
     */
    private String principalRefId;
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