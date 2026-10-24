package com.xspaceagi.connector.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 连接器提供商目录。
 */
@Data
@TableName("connector_provider")
public class ConnectorProvider {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField(value = "_tenant_id")
    private Long tenantId;

    /** 空间ID，0=官方/全局可见 */
    private Long spaceId;

    /** 唯一标识 github/feishu */
    private String service;

    private String displayName;

    /** 连接器介绍（目录卡片展示） */
    private String description;

    private String icon;

    private String category;

    /** 能力域分组标签（JSON 数组字符串） */
    private String tags;

    /** api_key/oauth2/bearer/custom/no_auth */
    private String authType;

    private String baseUrl;

    /** key注入位置/OAuth端点/scopes（JSON 字符串） */
    private String authConfig;

    /** OAuth App 模式：platform=全局公共App（配置一次全员共用）/ byo=个人App（每个用户连接时自填） */
    private String oauthAppMode;

    /** open_connector/nuwax/market */
    private String source;

    /** 上游目录版本，重导入 diff 基线 */
    private String providerVersion;

    /** system/user（user=手工保护区） */
    private String managedBy;

    /** 是否允许通用代理兜底 */
    private Integer proxyEnabled;

    /** enabled/disabled */
    private String status;

    /** 官方目录排序权重，越小越靠前；0=未排序（按 id） */
    private Integer sortOrder;

    private Date modified;

    private Date created;

    /**
     * 是否官方连接器：官方目录固定 space_id=0、全局可见、不属于任何空间。
     * 官方连接器发起连接时不做空间权限校验（无空间归属）。
     */
    public boolean isOfficial() {
        return spaceId != null && spaceId == 0L;
    }
}
