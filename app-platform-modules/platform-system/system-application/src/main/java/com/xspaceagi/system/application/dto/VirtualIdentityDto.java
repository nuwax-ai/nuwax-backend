package com.xspaceagi.system.application.dto;

import java.util.Date;

import com.xspaceagi.system.infra.dao.entity.User;

import lombok.Data;

/**
 * 虚拟身份（智能体身份 / 群代理身份）。
 * <p>虚拟身份是 user 表里 {@code account_type != 'user'} 的行：它代表某个非人主体
 * （智能体本身、或 IM 群），承载记忆与配置，但不代表任何真人，
 * 因而不会出现在用户列表、用户搜索、注册用户统计与通知广播里。
 * <p>刻意不复用 {@link UserDto}：调用方只需要「这个主体是谁、id 是多少」，
 * 不该拿到 password / resetPass / email / phone 这些自然人字段。
 */
@Data
public class VirtualIdentityDto {

    /** 身份用户 id：后续以该 id 作为主体（user_id）参与记忆、模型调用、会话等链路 */
    private Long id;

    private String uid;

    private Long tenantId;

    /** 账号类型，见 {@code User.ACCOUNT_TYPE_AGENT} / {@code User.ACCOUNT_TYPE_GROUP} */
    private String accountType;

    /** 身份关联标识：accountType=agent 时为 agent id；=group 时为 channel:sessionKey */
    private String principalRefId;

    private String nickName;

    private String avatar;

    private User.Status status;

    /** true = 本次新建；false = 命中已存在的身份（幂等复用，接口重复调用不会重复建主体） */
    private Boolean newlyCreated;

    /** 该身份的创建时间（复用已有身份时为原有创建时间） */
    private Date createTime;
}
