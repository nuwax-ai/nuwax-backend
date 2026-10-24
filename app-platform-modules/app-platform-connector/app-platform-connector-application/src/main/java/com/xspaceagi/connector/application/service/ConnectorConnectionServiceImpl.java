package com.xspaceagi.connector.application.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.connector.domain.command.CreateConnectionCommand;
import com.xspaceagi.connector.domain.entity.ConnectorConnection;
import com.xspaceagi.connector.domain.entity.ConnectorProvider;
import com.xspaceagi.connector.domain.service.ConnectorCatalogService;
import com.xspaceagi.connector.domain.service.ConnectorConnectionService;
import com.xspaceagi.connector.domain.service.ConnectorOAuthService;
import com.xspaceagi.connector.application.support.ConnectorFieldLimits;
import com.xspaceagi.connector.domain.service.CredentialVaultService;
import com.xspaceagi.connector.infra.repository.ConnectorConnectionRepository;
import com.xspaceagi.connector.spec.enums.AuthTypeEnum;
import com.xspaceagi.connector.spec.enums.ConnectionStatusEnum;
import com.xspaceagi.connector.spec.enums.CredTypeEnum;
import com.xspaceagi.connector.spec.error.ConnectorErrorCode;
import com.xspaceagi.connector.spec.exception.ConnectorException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户连接服务实现（个人连接 §9.2；凭证经 Vault 加密，边界外只见 profile）。
 */
@Slf4j
@Service
public class ConnectorConnectionServiceImpl implements ConnectorConnectionService {

    @Resource
    private ConnectorCatalogService catalogService;

    @Resource
    private ConnectorConnectionRepository connectionRepository;

    @Resource
    private CredentialVaultService vaultService;

    @Resource
    private ConnectorOAuthService oauthService;

    @Override
    public ConnectorConnection createDirectConnection(Long tenantId, Long userId, CreateConnectionCommand command) {
        if (command == null || StringUtils.isBlank(command.getProviderService())) {
            throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR, "providerService 必填");
        }
        ConnectorProvider provider = catalogService.requireProvider(command.getProviderService());
        // 官方连接器无空间归属，spaceId 置 0（官方语义）；空间自建连接器必须传 >0 的空间 ID。
        // 注意：不要写成 `isOfficial() ? 0L : getSpaceId()` 这类混合类型三元——0L 会把整个表达式
        // 提升为基本类型 long，包装类型那支为 null 时在求值处直接拆箱抛 NPE，让下面的校验形同虚设。
        Long spaceId;
        if (provider.isOfficial()) {
            spaceId = 0L;
        } else {
            Long commandSpaceId = command.getSpaceId();
            if (commandSpaceId == null || commandSpaceId <= 0) {
                throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR,
                        "spaceId 非法：空间自建连接器须传所属空间 ID（>0），官方连接器请勿传 spaceId");
            }
            spaceId = commandSpaceId;
        }
        if (!"enabled".equals(provider.getStatus())) {
            throw new ConnectorException(ConnectorErrorCode.PROVIDER_DISABLED);
        }
        AuthTypeEnum authType = AuthTypeEnum.fromCode(provider.getAuthType());
        // 两种 OAuth 机制都拒绝直填：手填的凭证没有配套的 refresh_token 与 expires_at，
        // 会在 7200 秒后变成一条无法自救的僵死连接。
        if (authType.isOauth2TokenFlow()) {
            throw new ConnectorException(ConnectorErrorCode.UNSUPPORTED_AUTH_TYPE,
                    authType == AuthTypeEnum.OAUTH2_DEVICE
                            ? "扫码连接器请通过扫码流程建立连接: /api/connector/oauth/device/authorize"
                            : "OAuth 连接器请通过授权流程建立连接: /api/connector/oauth/authorize");
        }

        Map<String, String> fields = command.getFields() == null ? Map.of() : command.getFields();
        validateAndNormalizeFields(authType, provider, fields);

        String credType = switch (authType) {
            case API_KEY -> CredTypeEnum.API_KEY.getCode();
            case BEARER -> CredTypeEnum.BEARER.getCode();
            case CUSTOM -> CredTypeEnum.CUSTOM_FIELDS.getCode();
            default -> CredTypeEnum.CUSTOM_FIELDS.getCode();
        };
        long credentialId = vaultService.store(credType, JSON.toJSONString(fields), null);

        ConnectorConnection connection = new ConnectorConnection();
        connection.setTenantId(tenantId);
        connection.setSpaceId(spaceId);
        connection.setUserId(userId);
        connection.setProviderService(provider.getService());
        // 别名缺省取 provider 展示名（可到 128），按 connector_connection.name 列宽 64 截断
        connection.setName(ConnectorFieldLimits.connectionAlias(command.getName(), provider.getDisplayName()));
        connection.setStatus(ConnectionStatusEnum.ACTIVE.getCode());
        // 显式置启用，不依赖列默认值：新建的连接默认就是可用的，用户可随时停用
        connection.setEnabled(true);
        connection.setCredentialId(credentialId);
        Date now = new Date();
        connection.setCreated(now);
        connection.setModified(now);
        connectionRepository.insert(connection);
        log.info("[connector] connection created: user={}, provider={}, connectionId={}",
                userId, provider.getService(), connection.getId());
        return connection;
    }

    @Override
    public List<ConnectorConnection> listMyConnections(Long tenantId, Long userId, String providerService) {
        return connectionRepository.listByUser(tenantId, userId, providerService);
    }

    @Override
    public ConnectorConnection requireOwned(Long connectionId, Long userId) {
        ConnectorConnection connection = connectionRepository.getOwned(connectionId, userId);
        if (connection == null) {
            throw new ConnectorException(ConnectorErrorCode.CONNECTION_NOT_FOUND);
        }
        return connection;
    }

    @Override
    public ConnectorConnection resolveForExecution(Long tenantId, Long userId, String providerService) {
        return connectionRepository.firstExecutable(tenantId, userId, providerService);
    }

    @Override
    public ConnectorConnection setEnabled(Long connectionId, Long userId, boolean enabled) {
        ConnectorConnection connection = requireOwned(connectionId, userId);
        // 只动用户开关，不碰 status——status 是凭证健康度，由系统写。
        // 停用一条已过期的连接时 expired 要原样留着：用户下次启用它时才知道该去重新授权，
        // 而不是被一句「启用成功」骗着去撞一个已经死掉的 token。
        connection.setEnabled(enabled);
        connection.setModified(new Date());
        connectionRepository.updateById(connection);
        log.info("[connector] connection {}: user={}, provider={}, connectionId={}",
                enabled ? "enabled" : "disabled", userId, connection.getProviderService(), connectionId);
        return connection;
    }

    @Override
    public void disconnect(Long connectionId, Long userId) {
        ConnectorConnection connection = requireOwned(connectionId, userId);
        // 凭证连同 credential 行删除（设计文档 §7）
        vaultService.delete(connection.getCredentialId());
        connectionRepository.deleteById(connection.getId());
        // 再回收该用户在这台连接器上「由扫码自注册产生」的 App 配置（scope_type=byo 那行）。
        // 漏掉这一步会留下一个走不出去的死局：beginDeviceFlow 的分岔判据是「该用户解析到的配置行
        // 有没有 client_id」（needsSelfRegistration），残留的 byo 行让判据恒为假，用户去 IdP 删掉
        // 那个应用之后就再也回不到自注册分支，只会拿着已经失效的 client_id 撞上游报错。
        // 两道保护都在 releaseRegisteredApp 内部：用户手填的 App 不回收，名下还有别的连接时也不回收。
        // 必须传 tenantId：该项判定要数该用户在该 provider 下还剩几条连接，而 tenantId 只在连接上。
        try {
            oauthService.releaseRegisteredApp(connection.getProviderService(), userId,
                    connection.getTenantId());
        } catch (Exception e) {
            // 回收是附属清理，主操作（凭证与连接行）此时已经落库。这里让异常冒出去，用户会看到
            // 「断连失败」，可重试时连接已不存在、直接 404，比一条告警更难排查。记下来即可：
            // 残留的配置只影响该用户下次重新扫码（会拿着失效的 client_id 撞上游），不影响本次断连。
            log.warn("[connector] 自注册 App 配置回收失败（不影响本次断连）: provider={}, connectionId={}",
                    connection.getProviderService(), connectionId, e);
        }
        log.info("[connector] connection disconnected: user={}, provider={}, connectionId={}",
                userId, connection.getProviderService(), connectionId);
    }

    /**
     * 按 provider auth_config 校验直填字段。
     */
    private void validateAndNormalizeFields(AuthTypeEnum authType, ConnectorProvider provider,
                                            Map<String, String> fields) {
        switch (authType) {
            case NO_AUTH -> {
            }
            case API_KEY -> {
                String keyName = apiKeyFieldName(provider);
                if (StringUtils.isBlank(fields.get(keyName))) {
                    throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR,
                            "缺少凭证字段: " + keyName);
                }
            }
            case BEARER -> {
                if (StringUtils.isBlank(fields.get("token"))) {
                    throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR, "缺少凭证字段: token");
                }
            }
            case CUSTOM -> {
                if (fields.isEmpty()) {
                    throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR, "custom 连接至少需要一个凭证字段");
                }
            }
            default -> throw new ConnectorException(ConnectorErrorCode.UNSUPPORTED_AUTH_TYPE);
        }
    }

    /**
     * api_key 注入字段名：优先 provider auth_config.keyName，缺省 apiKey。
     */
    public static String apiKeyFieldName(ConnectorProvider provider) {
        if (StringUtils.isNotBlank(provider.getAuthConfig())) {
            try {
                JSONObject authConfig = JSON.parseObject(provider.getAuthConfig());
                String keyName = authConfig.getString("keyName");
                if (StringUtils.isNotBlank(keyName)) {
                    return keyName;
                }
            } catch (Exception ignored) {
                // authConfig 非法时回退默认
            }
        }
        return "apiKey";
    }
}
