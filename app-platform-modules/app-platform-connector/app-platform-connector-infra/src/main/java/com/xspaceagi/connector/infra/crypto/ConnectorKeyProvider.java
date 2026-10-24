package com.xspaceagi.connector.infra.crypto;

import com.xspaceagi.connector.spec.ConnectorConstants;
import com.xspaceagi.connector.spec.error.ConnectorErrorCode;
import com.xspaceagi.connector.spec.exception.ConnectorException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Base64;

/**
 * 连接器加密主密钥提供者（设计文档 §4.2 / §8.1-4）。
 * <p>
 * 主密钥来源优先级：System property {@code NUWAX_CONNECTOR_ENCRYPTION_KEY}（便于测试）
 * &gt; Spring 配置 {@code nuwax.connector.encryption-key}（application.yml）&gt; 环境变量
 * {@code NUWAX_CONNECTOR_ENCRYPTION_KEY}。支持 Base64（32 字节）或 Hex（64 字符）两种形态。
 * 惰性校验：未配置时应用可正常启动（不破坏存量部署），首次使用 Vault 时才抛错；
 * 启动期由 {@link ConnectorCryptoStartupChecker} 打告警日志。
 */
@Slf4j
@Component
public class ConnectorKeyProvider {

    private static final int KEY_LENGTH_BYTES = 32;

    /**
     * Spring 配置来源的主密钥（application.yml 的 {@code nuwax.connector.encryption-key}）。
     * 优先级低于 System property、高于环境变量；空表示未通过配置提供。
     */
    @Value("${nuwax.connector.encryption-key:}")
    private String configKey;

    private volatile byte[] masterKey;

    /**
     * 获取 32 字节主密钥；未配置或非法时抛出 {@link ConnectorErrorCode#KEY_NOT_CONFIGURED}。
     */
    public byte[] requireMasterKey() {
        byte[] key = masterKey;
        if (key != null) {
            return key;
        }
        synchronized (this) {
            if (masterKey == null) {
                masterKey = loadKey();
            }
            return masterKey;
        }
    }

    /**
     * 密钥是否已配置（供启动告警与运维探针使用，不触发抛错）。
     */
    public boolean isConfigured() {
        if (masterKey != null) {
            return true;
        }
        return readRawValue() != null;
    }

    private byte[] loadKey() {
        String raw = readRawValue();
        if (raw == null || raw.isBlank()) {
            throw new ConnectorException(ConnectorErrorCode.KEY_NOT_CONFIGURED);
        }
        raw = raw.trim();
        byte[] decoded;
        try {
            if (raw.matches("[0-9a-fA-F]{64}")) {
                decoded = hexToBytes(raw);
            } else {
                decoded = Base64.getDecoder().decode(raw);
            }
        } catch (IllegalArgumentException e) {
            throw new ConnectorException(ConnectorErrorCode.KEY_NOT_CONFIGURED,
                    "加密主密钥格式非法：需为 32 字节 Base64 或 64 位 Hex");
        }
        if (decoded.length != KEY_LENGTH_BYTES) {
            throw new ConnectorException(ConnectorErrorCode.KEY_NOT_CONFIGURED,
                    "加密主密钥长度必须为 32 字节，实际 " + decoded.length + " 字节");
        }
        log.info("[connector] encryption master key loaded");
        return decoded;
    }

    /**
     * 读取优先级：System property（便于单元测试）> Spring 配置（yml）> 环境变量。
     */
    private String readRawValue() {
        String value = System.getProperty(ConnectorConstants.ENCRYPTION_KEY_ENV);
        if (value != null && !value.isBlank()) {
            return value;
        }
        if (configKey != null && !configKey.isBlank()) {
            return configKey;
        }
        return System.getenv(ConnectorConstants.ENCRYPTION_KEY_ENV);
    }

    private static byte[] hexToBytes(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}
