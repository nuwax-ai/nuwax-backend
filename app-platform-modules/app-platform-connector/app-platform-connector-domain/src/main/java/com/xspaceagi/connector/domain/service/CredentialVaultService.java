package com.xspaceagi.connector.domain.service;

import java.util.Date;

/**
 * 凭证保险库（设计文档 §4.2 凭证边界铁律）。
 * <p>
 * 铁律：凭证明文不出 connector 模块边界。本接口仅允许 connector 模块内部的
 * 执行引擎/OAuth 服务调用；跨模块一律只传 profile 与执行结果。
 */
public interface CredentialVaultService {

    /**
     * 加密并存储凭证 JSON，返回凭证行 id。
     *
     * @param credType  凭证类型（CredTypeEnum code）
     * @param plainJson 明文凭证 JSON（如 {"apiKey":"..."} / {"accessToken":"...","refreshToken":"..."}）
     * @param expiresAt 过期时间，可空
     */
    long store(String credType, String plainJson, Date expiresAt);

    /**
     * 解密读取凭证 JSON（模块内部使用）。
     */
    String reveal(long credentialId);

    /**
     * 读取凭证元信息（类型/过期时间/revision），不触明文。
     */
    Meta peek(long credentialId);

    /**
     * 乐观锁写回（OAuth 刷新轮换：新值覆盖，revision 不匹配则失败）。
     *
     * @return 是否写回成功
     */
    boolean writeBack(long credentialId, String plainJson, Date expiresAt, long expectedRevision);

    /**
     * 删除凭证行（断开连接时连同删除）。
     */
    void delete(long credentialId);

    /**
     * 凭证元信息（不含明文）。
     */
    record Meta(long id, String credType, Date expiresAt, long revision) {
    }
}
