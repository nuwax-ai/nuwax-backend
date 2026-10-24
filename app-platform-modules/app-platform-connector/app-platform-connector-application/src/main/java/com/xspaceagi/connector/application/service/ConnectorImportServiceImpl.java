package com.xspaceagi.connector.application.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.connector.application.support.ConnectorArgsCodec;
import com.xspaceagi.connector.application.support.HttpSpecCodec;
import com.xspaceagi.connector.domain.dto.ImportItemDto;
import com.xspaceagi.connector.domain.dto.ImportReportDto;
import com.xspaceagi.connector.domain.entity.ConnectorAction;
import com.xspaceagi.connector.domain.entity.ConnectorProvider;
import com.xspaceagi.connector.domain.service.ConnectorImportService;
import com.xspaceagi.connector.infra.repository.ConnectorActionRepository;
import com.xspaceagi.connector.infra.repository.ConnectorProviderRepository;
import com.xspaceagi.connector.spec.enums.ImportOpEnum;
import com.xspaceagi.connector.spec.enums.ManagedByEnum;
import com.xspaceagi.connector.spec.error.ConnectorErrorCode;
import com.xspaceagi.connector.spec.exception.ConnectorException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 批量导入管线实现（设计文档 §4.1：解析→dry-run→diff→人审→单事务批量落库）。
 * <p>
 * dry-run 会话保存在内存（30 分钟 TTL）；apply 时基于会话内 bundle 重算 diff，
 * 保证"所见即所得"且天然幂等。多副本部署下 dry-run 与 apply 需命中同一实例，
 * 未命中返回 IMPORT_DIFF_MISMATCH，前端重新 dry-run 即可。
 */
@Slf4j
@Service
public class ConnectorImportServiceImpl implements ConnectorImportService {

    private static final long SESSION_TTL_MS = 30 * 60 * 1000L;
    private static final long OFFICIAL_TENANT_ID = 1L;
    private static final long OFFICIAL_SPACE_ID = 0L;
    private static final ImportTarget OFFICIAL_TARGET =
            new ImportTarget(OFFICIAL_TENANT_ID, OFFICIAL_SPACE_ID, true);

    private final Map<String, ImportSession> sessions = new ConcurrentHashMap<>();

    @Resource
    private ConnectorProviderRepository providerRepository;

    @Resource
    private ConnectorActionRepository actionRepository;

    @Override
    public ImportReportDto dryRun(Long userId, JSONObject bundle) {
        return dryRunInternal(userId, bundle, OFFICIAL_TARGET);
    }

    @Override
    public ImportReportDto dryRunToSpace(Long userId, Long tenantId, Long spaceId, JSONObject bundle) {
        if (spaceId == null || spaceId == 0L) {
            throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR, "空间导入需要有效的 spaceId");
        }
        return dryRunInternal(userId, bundle, new ImportTarget(tenantId, spaceId, false));
    }

    private ImportReportDto dryRunInternal(Long userId, JSONObject bundle, ImportTarget target) {
        ImportBundle parsed = parseBundle(bundle);
        if (!target.official()) {
            // 空间导入：service 统一 s_ 前缀（幂等），dry-run 与 apply 基于同一归一化 bundle 一致
            normalizeSpaceServices(parsed);
        }
        ImportReportDto report = buildDiff(parsed, target);
        String importId = UUID.randomUUID().toString().replace("-", "");
        sessions.put(importId, new ImportSession(importId, userId, parsed, target, System.currentTimeMillis()));
        evictExpiredSessions();
        report.setImportId(importId);
        return report;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImportReportDto apply(Long userId, String importId) {
        ImportSession session = requireSession(importId, userId);
        if (!session.target.official()) {
            throw new ConnectorException(ConnectorErrorCode.IMPORT_DIFF_MISMATCH,
                    "该会话为空间导入，请走空间 apply 接口");
        }
        return applyInternal(userId, importId, session);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImportReportDto applyToSpace(Long userId, Long spaceId, String importId) {
        ImportSession session = requireSession(importId, userId);
        if (session.target.official() || !Objects.equals(session.target.spaceId(), spaceId)) {
            throw new ConnectorException(ConnectorErrorCode.IMPORT_DIFF_MISMATCH,
                    "导入会话与目标空间不匹配，请重新 dry-run");
        }
        return applyInternal(userId, importId, session);
    }

    /** 空间导入 service 统一 s_ 前缀（幂等：已带 s_ 不重复追加），标识空间私有；改 raw 引用，dry-run 与 apply 一致 */
    private void normalizeSpaceServices(ImportBundle bundle) {
        for (ProviderEntry entry : bundle.providers()) {
            String svc = entry.raw().getString("service");
            if (svc != null && !svc.startsWith("s_")) {
                entry.raw().put("service", "s_" + svc);
            }
        }
    }

    private ImportSession requireSession(String importId, Long userId) {
        ImportSession session = sessions.get(importId);
        if (session == null || !Objects.equals(session.userId, userId)
                || System.currentTimeMillis() - session.createdAt > SESSION_TTL_MS) {
            throw new ConnectorException(ConnectorErrorCode.IMPORT_DIFF_MISMATCH);
        }
        return session;
    }

    private ImportReportDto applyInternal(Long userId, String importId, ImportSession session) {
        // 重算 diff 并落库（单事务）
        ImportReportDto report = applyDiff(session.bundle, session.target);
        sessions.remove(importId);
        log.info("[connector] import applied by user {} (target space {}): {} add, {} update, {} unchanged, {} skipped",
                userId, session.target.spaceId(), report.getAddCount(), report.getUpdateCount(),
                report.getUnchangedCount(), report.getSkipProtectedCount());
        return report;
    }

    @Override
    public JSONObject export(String service) {
        ConnectorProvider provider = providerRepository.getByService(service);
        if (provider == null) {
            throw new ConnectorException(ConnectorErrorCode.PROVIDER_NOT_FOUND, "连接器不存在: " + service);
        }
        return exportByServices(List.of(service));
    }

    @Override
    public JSONObject exportByServices(List<String> services) {
        JSONObject bundle = new JSONObject();
        JSONArray providers = new JSONArray();
        String source = null;
        String providerVersion = null;
        for (String service : services) {
            ConnectorProvider provider = providerRepository.getByService(service);
            if (provider == null) {
                throw new ConnectorException(ConnectorErrorCode.PROVIDER_NOT_FOUND, "连接器不存在: " + service);
            }
            if (source == null) source = provider.getSource();
            if (providerVersion == null) providerVersion = provider.getProviderVersion();
            providers.add(buildProviderJson(provider));
        }
        bundle.put("source", source);
        bundle.put("providerVersion", providerVersion);
        bundle.put("providers", providers);
        return bundle;
    }

    private JSONObject buildProviderJson(ConnectorProvider provider) {
        List<ConnectorAction> actions = actionRepository.listByProvider(provider.getService(), null, null, true, true);
        JSONObject providerJson = new JSONObject();
        providerJson.put("service", provider.getService());
        providerJson.put("displayName", provider.getDisplayName());
        providerJson.put("icon", provider.getIcon());
        providerJson.put("category", provider.getCategory());
        providerJson.put("tags", parseJson(provider.getTags()));
        providerJson.put("authType", provider.getAuthType());
        providerJson.put("baseUrl", provider.getBaseUrl());
        providerJson.put("authConfig", parseJson(provider.getAuthConfig()));
        providerJson.put("proxyEnabled", provider.getProxyEnabled() != null && provider.getProxyEnabled() == 1);
        JSONArray actionArray = new JSONArray();
        for (ConnectorAction action : actions) {
            JSONObject actionJson = new JSONObject();
            actionJson.put("actionKey", action.getActionKey());
            actionJson.put("name", action.getName());
            actionJson.put("description", action.getDescription());
            actionJson.put("tags", parseJson(action.getTags()));
            actionJson.put("inputArgs", parseJson(action.getInputArgs()));
            actionJson.put("outputArgs", parseJson(action.getOutputArgs()));
            actionJson.put("execType", action.getExecType());
            actionJson.put("httpSpec", parseJson(action.getHttpSpec()));
            actionJson.put("deprecated", action.getDeprecated() != null && action.getDeprecated() == 1);
            actionArray.add(actionJson);
        }
        providerJson.put("actions", actionArray);
        return providerJson;
    }

    // ---------- 内部 ----------

    private record ImportSession(String importId, Long userId, ImportBundle bundle,
                                 ImportTarget target, long createdAt) {
    }

    /**
     * 导入目标。official=官方目录（space_id=0，managed_by=system）；
     * 否则为空间自定义目录（managed_by=user）。
     */
    private record ImportTarget(Long tenantId, Long spaceId, boolean official) {
    }

    private record ImportBundle(String source, String providerVersion, List<ProviderEntry> providers) {
    }

    private record ProviderEntry(JSONObject raw, List<JSONObject> actions) {
    }

    private ImportBundle parseBundle(JSONObject bundle) {
        if (bundle == null || !bundle.containsKey("providers")) {
            throw new ConnectorException(ConnectorErrorCode.IMPORT_BUNDLE_INVALID, "导入包缺少 providers 数组");
        }
        JSONArray providers = bundle.getJSONArray("providers");
        if (providers == null || providers.isEmpty()) {
            throw new ConnectorException(ConnectorErrorCode.IMPORT_BUNDLE_INVALID, "providers 为空");
        }
        List<ProviderEntry> entries = new ArrayList<>();
        for (int i = 0; i < providers.size(); i++) {
            JSONObject provider = providers.getJSONObject(i);
            if (StringUtils.isBlank(provider.getString("service"))) {
                throw new ConnectorException(ConnectorErrorCode.IMPORT_BUNDLE_INVALID, "providers[" + i + "] 缺少 service");
            }
            if (StringUtils.isBlank(provider.getString("authType"))) {
                throw new ConnectorException(ConnectorErrorCode.IMPORT_BUNDLE_INVALID,
                        "provider " + provider.getString("service") + " 缺少 authType");
            }
            checkProviderWidths(provider);
            JSONArray actions = provider.getJSONArray("actions");
            List<JSONObject> actionList = new ArrayList<>();
            if (actions != null) {
                for (int j = 0; j < actions.size(); j++) {
                    JSONObject action = actions.getJSONObject(j);
                    if (StringUtils.isBlank(action.getString("actionKey"))) {
                        throw new ConnectorException(ConnectorErrorCode.IMPORT_BUNDLE_INVALID,
                                "provider " + provider.getString("service") + " 的 actions[" + j + "] 缺少 actionKey");
                    }
                    checkActionWidths(provider.getString("service"), action);
                    if (action.get("inputArgs") != null) {
                        // 类型化 Arg 树校验归一（旧扁平 map 在此被拒绝）
                        action.put("inputArgs", ConnectorArgsCodec.normalizeToJson(action.get("inputArgs"),
                                "provider " + provider.getString("service") + " 的 actions[" + j + "].inputArgs"));
                    }
                    if (action.get("httpSpec") != null) {
                        // httpSpec 结构校验归一。放在 parseBundle（dry-run 与 apply 都会走）而不是
                        // 落库处：坏 spec 要在人审之前就被指出来，而不是进了库、等调用方执行才炸。
                        action.put("httpSpec", HttpSpecCodec.normalize(action.get("httpSpec"),
                                "provider " + provider.getString("service") + " 的 actions[" + j + "].httpSpec"));
                    }
                    actionList.add(action);
                }
            }
            entries.add(new ProviderEntry(provider, actionList));
        }
        String source = StringUtils.defaultIfBlank(bundle.getString("source"), "open_connector");
        String providerVersion = bundle.getString("providerVersion");
        // 对应 connector_provider.source 32 / provider_version 64
        requireWithin(source, 32, "导入包", "source");
        requireWithin(providerVersion, 64, "导入包", "providerVersion");
        return new ImportBundle(source, providerVersion, entries);
    }

    /**
     * 列宽守卫：上游目录字段超出 DB 列宽时报明确错误。
     * 放在 parseBundle（dry-run 与 apply 都会走）而不是落库处，
     * 让问题在人审之前暴露，而不是撞上 DB 的 data too long 变成「系统异常」。
     */
    private void requireWithin(String value, int max, String where, String field) {
        if (value != null && value.length() > max) {
            throw new ConnectorException(ConnectorErrorCode.IMPORT_BUNDLE_INVALID,
                    where + " 的 " + field + " 超长（最长 " + max + " 字符，实际 " + value.length() + "）");
        }
    }

    /** 对应 connector_provider 列宽：service 64 / display_name 128 / icon 255 / category 64 / auth_type 32 / base_url 512 */
    private void checkProviderWidths(JSONObject provider) {
        String where = "provider " + provider.getString("service");
        requireWithin(provider.getString("service"), 64, where, "service");
        requireWithin(provider.getString("displayName"), 128, where, "displayName");
        requireWithin(provider.getString("icon"), 255, where, "icon");
        requireWithin(provider.getString("category"), 64, where, "category");
        requireWithin(provider.getString("authType"), 32, where, "authType");
        requireWithin(provider.getString("baseUrl"), 512, where, "baseUrl");
    }

    /** 对应 connector_action 列宽：action_key 128 / name 128 / exec_type 16 / exec_ref 255；description 为 text 列不设限 */
    private void checkActionWidths(String service, JSONObject action) {
        String where = "provider " + service + " 的 action " + action.getString("actionKey");
        requireWithin(action.getString("actionKey"), 128, where, "actionKey");
        requireWithin(action.getString("name"), 128, where, "name");
        requireWithin(action.getString("execType"), 16, where, "execType");
        requireWithin(action.getString("execRef"), 255, where, "execRef");
    }

    /**
     * 计算 diff（不落库）。
     */
    private ImportReportDto buildDiff(ImportBundle bundle, ImportTarget target) {
        ImportReportDto report = new ImportReportDto();
        report.setSource(bundle.source());
        report.setProviderVersion(bundle.providerVersion());
        List<ImportItemDto> items = new ArrayList<>();
        for (ProviderEntry entry : bundle.providers()) {
            String service = entry.raw().getString("service");
            items.add(diffProvider(service, entry.raw(), target));
            for (JSONObject action : entry.actions()) {
                items.add(diffAction(service, action, target));
            }
        }
        report.setItems(items);
        for (ImportItemDto item : items) {
            switch (ImportOpEnum.valueOf(item.getOp().toUpperCase())) {
                case ADD -> report.setAddCount(report.getAddCount() + 1);
                case UPDATE -> report.setUpdateCount(report.getUpdateCount() + 1);
                case UNCHANGED -> report.setUnchangedCount(report.getUnchangedCount() + 1);
                case SKIP_PROTECTED -> report.setSkipProtectedCount(report.getSkipProtectedCount() + 1);
                default -> {
                }
            }
        }
        return report;
    }

    private ImportItemDto diffProvider(String service, JSONObject incoming, ImportTarget target) {
        ImportItemDto item = new ImportItemDto();
        item.setType("provider");
        item.setService(service);
        ConnectorProvider existing = providerRepository.getByService(service);
        if (existing == null) {
            item.setOp(ImportOpEnum.ADD.getCode());
            return item;
        }
        if (target.official()) {
            if (ManagedByEnum.USER.getCode().equals(existing.getManagedBy())) {
                item.setOp(ImportOpEnum.SKIP_PROTECTED.getCode());
                item.setReason("managed_by=user 手工保护区");
                return item;
            }
        } else if (!target.spaceId().equals(existing.getSpaceId())) {
            // service 全局唯一：已被官方目录或其他空间占用，空间导入不覆盖
            item.setOp(ImportOpEnum.SKIP_PROTECTED.getCode());
            item.setReason("service 已存在于官方目录或其他空间");
            return item;
        }
        boolean changed = !Objects.equals(incoming.getString("displayName"), existing.getDisplayName())
                || !Objects.equals(incoming.getString("icon"), existing.getIcon())
                || !Objects.equals(incoming.getString("category"), existing.getCategory())
                || !Objects.equals(incoming.getString("authType"), existing.getAuthType())
                || !Objects.equals(incoming.getString("baseUrl"), existing.getBaseUrl())
                || !Objects.equals(canonical(incoming.get("tags")), existing.getTags())
                || !Objects.equals(canonical(incoming.get("authConfig")), existing.getAuthConfig());
        item.setOp(changed ? ImportOpEnum.UPDATE.getCode() : ImportOpEnum.UNCHANGED.getCode());
        return item;
    }

    private ImportItemDto diffAction(String service, JSONObject incoming, ImportTarget target) {
        ImportItemDto item = new ImportItemDto();
        item.setType("action");
        item.setService(service);
        item.setActionKey(incoming.getString("actionKey"));
        if (!target.official()) {
            ConnectorProvider provider = providerRepository.getByService(service);
            if (provider != null && !target.spaceId().equals(provider.getSpaceId())) {
                item.setOp(ImportOpEnum.SKIP_PROTECTED.getCode());
                item.setReason("service 已存在于官方目录或其他空间");
                return item;
            }
        }
        ConnectorAction existing = actionRepository.getByKey(service, incoming.getString("actionKey"));
        if (existing == null) {
            item.setOp(ImportOpEnum.ADD.getCode());
            return item;
        }
        if (target.official() && ManagedByEnum.USER.getCode().equals(existing.getManagedBy())) {
            item.setOp(ImportOpEnum.SKIP_PROTECTED.getCode());
            item.setReason("managed_by=user 手工保护区");
            return item;
        }
        boolean changed = !Objects.equals(incoming.getString("name"), existing.getName())
                || !Objects.equals(incoming.getString("description"), existing.getDescription())
                || !Objects.equals(StringUtils.defaultIfBlank(incoming.getString("execType"), "DECLARATIVE"),
                existing.getExecType())
                || !Objects.equals(canonical(incoming.get("tags")), existing.getTags())
                || !Objects.equals(canonical(incoming.get("inputArgs")), existing.getInputArgs())
                || !Objects.equals(canonical(incoming.get("outputArgs")), existing.getOutputArgs())
                || !Objects.equals(canonical(incoming.get("httpSpec")), existing.getHttpSpec());
        item.setOp(changed ? ImportOpEnum.UPDATE.getCode() : ImportOpEnum.UNCHANGED.getCode());
        return item;
    }

    /**
     * 应用 diff 并落库（调用方已开事务）。
     */
    private ImportReportDto applyDiff(ImportBundle bundle, ImportTarget target) {
        ImportReportDto report = new ImportReportDto();
        report.setSource(bundle.source());
        report.setProviderVersion(bundle.providerVersion());
        List<ImportItemDto> items = new ArrayList<>();
        Date now = new Date();
        for (ProviderEntry entry : bundle.providers()) {
            String service = entry.raw().getString("service");
            items.add(applyProvider(service, entry.raw(), bundle, now, target));
            for (JSONObject action : entry.actions()) {
                items.add(applyAction(service, action, now, target));
            }
        }
        report.setItems(items);
        for (ImportItemDto item : items) {
            switch (ImportOpEnum.valueOf(item.getOp().toUpperCase())) {
                case ADD -> report.setAddCount(report.getAddCount() + 1);
                case UPDATE -> report.setUpdateCount(report.getUpdateCount() + 1);
                case UNCHANGED -> report.setUnchangedCount(report.getUnchangedCount() + 1);
                case SKIP_PROTECTED -> report.setSkipProtectedCount(report.getSkipProtectedCount() + 1);
                default -> {
                }
            }
        }
        return report;
    }

    private ImportItemDto applyProvider(String service, JSONObject incoming, ImportBundle bundle,
                                        Date now, ImportTarget target) {
        ImportItemDto item = diffProvider(service, incoming, target);
        if (ImportOpEnum.ADD.getCode().equals(item.getOp())) {
            ConnectorProvider provider = new ConnectorProvider();
            provider.setTenantId(target.tenantId());
            provider.setSpaceId(target.spaceId());
            provider.setService(service);
            fillProviderFields(provider, incoming);
            provider.setSource(bundle.source());
            provider.setProviderVersion(bundle.providerVersion());
            provider.setManagedBy(target.official()
                    ? ManagedByEnum.SYSTEM.getCode() : ManagedByEnum.USER.getCode());
            provider.setProxyEnabled(incoming.getBooleanValue("proxyEnabled") ? 1 : 0);
            provider.setStatus("enabled");
            provider.setSortOrder(providerRepository.nextSortOrder());
            provider.setCreated(now);
            provider.setModified(now);
            providerRepository.insert(provider);
        } else if (ImportOpEnum.UPDATE.getCode().equals(item.getOp())) {
            ConnectorProvider existing = providerRepository.getByService(service);
            fillProviderFields(existing, incoming);
            existing.setProviderVersion(bundle.providerVersion());
            existing.setModified(now);
            providerRepository.updateById(existing);
        }
        return item;
    }

    private void fillProviderFields(ConnectorProvider provider, JSONObject incoming) {
        provider.setDisplayName(StringUtils.defaultIfBlank(incoming.getString("displayName"),
                provider.getService()));
        provider.setIcon(incoming.getString("icon"));
        provider.setCategory(incoming.getString("category"));
        provider.setTags(canonical(incoming.get("tags")));
        provider.setAuthType(incoming.getString("authType"));
        provider.setBaseUrl(incoming.getString("baseUrl"));
        provider.setAuthConfig(canonical(incoming.get("authConfig")));
    }

    private ImportItemDto applyAction(String service, JSONObject incoming, Date now, ImportTarget target) {
        ImportItemDto item = diffAction(service, incoming, target);
        if (ImportOpEnum.SKIP_PROTECTED.getCode().equals(item.getOp())
                || ImportOpEnum.UNCHANGED.getCode().equals(item.getOp())) {
            return item;
        }
        ConnectorAction existing = ImportOpEnum.UPDATE.getCode().equals(item.getOp())
                ? actionRepository.getByKey(service, incoming.getString("actionKey"))
                : null;
        ConnectorAction action = existing == null ? new ConnectorAction() : existing;
        action.setProviderService(service);
        action.setActionKey(incoming.getString("actionKey"));
        action.setName(StringUtils.defaultIfBlank(incoming.getString("name"), incoming.getString("actionKey")));
        action.setDescription(incoming.getString("description"));
        action.setTags(canonical(incoming.get("tags")));
        action.setInputArgs(canonical(incoming.get("inputArgs")));
        action.setOutputArgs(canonical(incoming.get("outputArgs")));
        action.setExecType(StringUtils.defaultIfBlank(incoming.getString("execType"), "DECLARATIVE"));
        action.setHttpSpec(canonical(incoming.get("httpSpec")));
        action.setManagedBy(target.official()
                ? ManagedByEnum.SYSTEM.getCode() : ManagedByEnum.USER.getCode());
        action.setModified(now);
        if (existing == null) {
            action.setDeprecated(0);
            action.setStatus("enabled");
            action.setCreated(now);
            actionRepository.insert(action);
        } else {
            actionRepository.updateById(action);
        }
        return item;
    }

    /**
     * JSON 字段规范化：对象/数组 → 紧凑 JSON 字符串；字符串原样；null/空 → null。
     */
    private String canonical(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s.isBlank() ? null : s;
        }
        return JSON.toJSONString(value);
    }

    private Object parseJson(String json) {
        if (StringUtils.isBlank(json)) {
            return null;
        }
        try {
            return JSON.parse(json);
        } catch (Exception e) {
            return json;
        }
    }

    private void evictExpiredSessions() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, ImportSession>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().createdAt > SESSION_TTL_MS) {
                it.remove();
            }
        }
    }
}
