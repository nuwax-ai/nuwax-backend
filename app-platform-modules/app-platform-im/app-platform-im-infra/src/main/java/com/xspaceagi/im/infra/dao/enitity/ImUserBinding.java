package com.xspaceagi.im.infra.dao.enitity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 平台用户与 IM 渠道个人身份绑定。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("im_user_binding")
public class ImUserBinding {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 渠道：feishu / dingtalk / wework */
    private String channel;

    /** 平台用户 ID */
    private Long userId;

    /** IM 侧用户标识（飞书 open_id / 钉钉 userid / 企微 userid） */
    private String imUserId;

    private String displayName;

    /** 绑定所用的 im_channel_config.id */
    private Long channelConfigId;

    /** oauth_app / oauth_web / im_session / admin */
    private String source;

    @TableField("_tenant_id")
    private Long tenantId;

    private Date created;
    private Date modified;
}
