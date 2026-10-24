package com.xspaceagi.connector.infra.crypto;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 启动期密钥检查：不阻断启动（避免未配置密钥时破坏存量部署），仅打告警。
 * 首次使用 Vault 时才会真正抛错（见 {@link ConnectorKeyProvider}）。
 */
@Slf4j
@Component
public class ConnectorCryptoStartupChecker {

    @Resource
    private ConnectorKeyProvider keyProvider;

    @PostConstruct
    public void check() {
        if (!keyProvider.isConfigured()) {
            log.warn("[connector] 环境变量 NUWAX_CONNECTOR_ENCRYPTION_KEY 未配置，"
                    + "连接器凭证保险库在首次使用前不可用。请配置 32 字节 Base64 或 64 位 Hex 主密钥。");
        }
    }
}
