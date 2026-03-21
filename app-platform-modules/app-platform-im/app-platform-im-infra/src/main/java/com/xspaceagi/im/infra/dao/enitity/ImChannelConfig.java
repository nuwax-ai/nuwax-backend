package com.xspaceagi.im.infra.dao.enitity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xspaceagi.im.infra.enums.ImChannelEnum;
import com.xspaceagi.im.infra.enums.ImOutputModeEnum;
import com.xspaceagi.im.infra.enums.ImTargetTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * IM 渠道机器人/应用配置实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("im_channel_config")
public class ImChannelConfig {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 平台
     * @see ImChannelEnum
     */
    private String channel;

    /**
     * 渠道目标类型
     * @see ImTargetTypeEnum
     */
    private String targetType;

    /**
     * 渠道目标唯一标识
     */
    private String targetId;

    /**
     * 关联系统用户ID
     */
    private Long userId;

    /**
     * 关联智能体ID
     */
    private Long agentId;

    /**
     * 是否启用
     */
    private Boolean enabled;

    /**
     * 渠道专有配置（JSON 字符串）
     */
    private String configData;

    /**
     * 输出方式
     * @see ImOutputModeEnum
     */
    private String outputMode;

    /**
     * 配置名称备注
     */
    private String name;

    /**
     * 租户ID
     */
    @TableField("_tenant_id")
    private Long tenantId;
    private Long spaceId;
    private Date created;
    private Long creatorId;
    private String creatorName;
    private Date modified;
    private Long modifiedId;
    private String modifiedName;
    private Integer yn;
}
