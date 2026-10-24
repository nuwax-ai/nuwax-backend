package com.xspaceagi.connector.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 凭证保险库行（加密存储，无租户列：仅经 connection 间接访问，最小权限）。
 */
@Data
@TableName("connector_credential")
public class ConnectorCredential {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** api_key/oauth2_token/bearer/custom_fields */
    private String credType;

    /** enc:v1:{salt}:{iv}:{tag}:{data} AES-256-GCM */
    private String ciphertext;

    /** 凭证过期时间（OAuth access_token） */
    private Date expiresAt;

    /** 每次刷新+1，乐观锁防并发覆盖 */
    private Long revision;

    private Date created;

    private Date modified;
}
