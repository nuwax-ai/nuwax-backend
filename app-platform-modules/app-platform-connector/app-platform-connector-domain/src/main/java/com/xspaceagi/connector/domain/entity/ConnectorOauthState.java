package com.xspaceagi.connector.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * OAuth state（一次性，防重放）。主键为 state 的 SHA-256 哈希。
 */
@Data
@TableName("connector_oauth_state")
public class ConnectorOauthState {

    @TableId(value = "state_hash", type = IdType.INPUT)
    private String stateHash;

    private String providerService;

    private Long userId;

    private Long spaceId;

    /**
     * 授权码流程：PKCE verifier 加密存储。
     * <p>
     * 设备码（扫码）流程复用本列存<b>加密后的 device_code</b>。两者语义同类——
     * 都是「建连过程中的一次性秘密、只在服务端使用、生命周期与 state 完全相同」，
     * 且互斥（一个 state 只属于一种流程）。复用而非加列，是因为本表在线上库已存在，
     * 加列要走 DDL。列类型为 text，实测 100 字符的 device_code 加密后约 204 字符，容量充裕。
     */
    private String pkceVerifierEnc;

    /**
     * 授权码流程：完成页回跳目标（<b>当前无任何读写，是死列</b>）。
     * <p>
     * 设备码（扫码）流程复用本列存<b>加密后的 verification_uri_complete</b>，供
     * {@code GET /api/connector/oauth/device/qr} 按 state 渲染二维码——授权 URL 因此
     * 全程不经客户端。实测飞书返回的该 URL 约 122 字符，加密后约 233 字符，
     * 落在 varchar(512) 内。
     */
    private String redirectTarget;

    /** 是否已原子消费 */
    private Integer consumed;

    /** 15 分钟 TTL */
    private Date expiresAt;

    private Date created;
}
