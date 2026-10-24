package com.xspaceagi.connector.ui.web.converter;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.connector.domain.entity.ConnectorAction;
import com.xspaceagi.connector.domain.entity.ConnectorConnection;
import com.xspaceagi.connector.domain.entity.ConnectorProvider;
import com.xspaceagi.connector.ui.web.controller.dto.ActionDto;
import com.xspaceagi.connector.ui.web.controller.dto.ProviderDto;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 实体 → 视图 DTO 转换（JSON 列解析为结构化对象输出）。
 */
public final class ConnectorDtoConverter {

    private ConnectorDtoConverter() {
    }

    public static ProviderDto toProviderDto(ConnectorProvider provider) {
        if (provider == null) {
            return null;
        }
        ProviderDto dto = new ProviderDto();
        dto.setId(provider.getId());
        dto.setSpaceId(provider.getSpaceId());
        dto.setService(provider.getService());
        dto.setDisplayName(provider.getDisplayName());
        dto.setDescription(provider.getDescription());
        dto.setIcon(provider.getIcon());
        dto.setCategory(provider.getCategory());
        dto.setTags(parseStringList(provider.getTags()));
        dto.setAuthType(provider.getAuthType());
        dto.setBaseUrl(provider.getBaseUrl());
        dto.setAuthConfig(parseJson(provider.getAuthConfig()));
        dto.setOauthAppMode(provider.getOauthAppMode());
        dto.setSource(provider.getSource());
        dto.setProviderVersion(provider.getProviderVersion());
        dto.setManagedBy(provider.getManagedBy());
        dto.setProxyEnabled(provider.getProxyEnabled() != null && provider.getProxyEnabled() == 1);
        dto.setStatus(provider.getStatus());
        dto.setSortOrder(provider.getSortOrder());
        dto.setModified(provider.getModified());
        dto.setCreated(provider.getCreated());
        return dto;
    }

    public static ActionDto toActionDto(ConnectorAction action) {
        if (action == null) {
            return null;
        }
        ActionDto dto = new ActionDto();
        dto.setId(action.getId());
        dto.setProviderService(action.getProviderService());
        dto.setActionKey(action.getActionKey());
        dto.setName(action.getName());
        dto.setDescription(action.getDescription());
        dto.setTags(parseStringList(action.getTags()));
        dto.setInputArgs(parseJson(action.getInputArgs()));
        dto.setOutputArgs(parseJson(action.getOutputArgs()));
        dto.setExecType(action.getExecType());
        dto.setExecRef(action.getExecRef());
        dto.setHttpSpec(parseJson(action.getHttpSpec()));
        dto.setManagedBy(action.getManagedBy());
        dto.setDeprecated(action.getDeprecated() != null && action.getDeprecated() == 1);
        dto.setStatus(action.getStatus());
        dto.setModified(action.getModified());
        dto.setCreated(action.getCreated());
        return dto;
    }

    /**
     * 连接 → provider 视图里内嵌的连接摘要（只有 profile，不碰凭证）。
     */
    public static ProviderDto.ConnectionBrief toConnectionBrief(ConnectorConnection connection) {
        if (connection == null) {
            return null;
        }
        ProviderDto.ConnectionBrief brief = new ProviderDto.ConnectionBrief();
        brief.setId(connection.getId());
        brief.setSpaceId(connection.getSpaceId());
        brief.setName(connection.getName());
        brief.setStatus(connection.getStatus());
        brief.setEnabled(connection.getEnabled());
        brief.setIdentity(readIdentity(connection));
        return brief;
    }

    /**
     * 从 oauth_meta 里读建连时写下的 {@code identity}。
     * <p>
     * 读不到就返回 null 而不是兜底成 {@code user}：存量连接（以及所有非 OAuth 建连方式，
     * 如 api_key/静态令牌）都没有这个键，凭默认值猜出来的身份会直接显示给用户——
     * 「未知」比「猜错」安全，界面按 null 不显示身份标注即可。
     * <p>
     * 连接视图与 provider 视图都要这个字段，实现放这里共用，免得两处规则漂开。
     */
    public static String readIdentity(ConnectorConnection connection) {
        Object meta = parseJson(connection.getOauthMeta());
        if (!(meta instanceof Map<?, ?> map)) {
            return null;
        }
        Object identity = map.get("identity");
        return identity == null ? null : String.valueOf(identity);
    }

    public static List<String> parseStringList(String json) {
        if (StringUtils.isBlank(json)) {
            return List.of();
        }
        try {
            return JSON.parseArray(json, String.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    public static Object parseJson(String json) {
        if (StringUtils.isBlank(json)) {
            return null;
        }
        try {
            return JSON.parse(json);
        } catch (Exception e) {
            return json;
        }
    }
}
