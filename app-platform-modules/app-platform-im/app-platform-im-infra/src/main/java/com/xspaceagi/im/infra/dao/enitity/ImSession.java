package com.xspaceagi.im.infra.dao.enitity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.xspaceagi.im.infra.enums.ImChannelEnum;
import com.xspaceagi.im.infra.enums.ImChatTypeEnum;
import com.xspaceagi.im.infra.enums.ImTargetTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * IM会话实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImSession {

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 渠道类型
     * @see ImChannelEnum
     */
    private String channel;

    /**
     * 渠道目标类型
     * @see ImTargetTypeEnum
     */
    private String targetType;

    /**
     * 会话类型
     * @see ImChatTypeEnum
     */
    private String chatType;

    /**
     * 会话标识：单聊为用户ID，群聊为群ID
     */
    private String sessionKey;

    /**
     * 会话展示名：单聊为用户名/昵称，群聊为群名
     */
    private String sessionName;

    /**
     * 系统用户ID
     */
    private Long userId;

    /**
     * 智能体ID
     */
    private Long agentId;

    /**
     * 系统会话ID
     */
    private Long conversationId;

    /**
     * 租户ID
     */
    @TableField("_tenant_id")
    private Long tenantId;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;
}

