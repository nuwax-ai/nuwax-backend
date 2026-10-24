package com.xspaceagi.connector.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 用户连接（授权实例，本期只做个人连接）。
 */
@Data
@TableName("connector_connection")
public class ConnectorConnection {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField(value = "_tenant_id")
    private Long tenantId;

    private Long spaceId;

    /** 归属用户（个人连接） */
    private Long userId;

    private String providerService;

    /** 用户起的别名 */
    private String name;

    /** 凭证健康度：active/expired/error（disconnected 为历史兼容值，代码不再写入）。由系统写 */
    private String status;

    /**
     * 用户启用开关：true 启用 / false 停用。由用户控制，系统流程（建连、token 刷新）不得改写。
     * <p>
     * 与 {@link #status} 正交——停用一条已过期的连接时，status 仍然是 expired。
     * 一条连接能否被执行解析选中，取决于两者同时成立：{@code enabled && status == 'active'}。
     */
    private Boolean enabled;

    /** accountId/displayName/avatar，唯一可暴露给 LLM 的信息（JSON 字符串） */
    private String profile;

    /** 已授权 scope 列表（JSON 数组字符串） */
    private String grantedScopes;

    /** 凭证行ID */
    private Long credentialId;

    /** refresh 到期/revision 等（JSON 字符串） */
    private String oauthMeta;

    private Date modified;

    private Date created;
}
