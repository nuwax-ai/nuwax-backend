package com.xspaceagi.ctl.oauth2;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.xspaceagi.agent.core.adapter.application.ModelApplicationService;
import com.xspaceagi.agent.core.adapter.dto.config.ModelConfigDto;
import com.xspaceagi.agent.core.sdk.IModelRpcService;
import com.xspaceagi.agent.core.sdk.dto.ModelInfoDto;
import com.xspaceagi.agent.core.spec.enums.UsageScenarioEnum;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import com.xspaceagi.system.sdk.service.UserAccessKeyApiService;
import com.xspaceagi.system.sdk.service.dto.UserAccessKeyDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * OAuth2 门面 —— 模型组（可用模型查询、创建模型 API Key 并授权、通过该 Key 调用模型）。
 * <p>
 * scope：{@code model:read}（查可用模型）、{@code model:key}（签发 / 查看 / 改授权 / 吊销 Key）。
 * 两者都是<b>仅授权码模式</b>：签出的 Key 直接产生模型调用与计费，必须经本人授权。
 *
 * <h2>★为什么 Key 用 {@code AgentModel} 型而不是 {@code OpenApi} 型</h2>
 *
 * 现成的 {@code UserApiKeyApplicationService.createUserApiKey} 建的是 {@code OpenApi} 型且
 * {@code config.apiConfigs == null}，而 {@code ApiKeyInterceptor.completeAuthContext} 对
 * {@code apiConfigs} 为空的口径是「<b>全部接口权限</b>」。用它来满足「创建模型 API Key」，
 * 等于让第三方拿到一把远超其 OAuth2 scope 的万能钥匙（权限升级）。
 * <p>
 * {@code AgentModel} 型则相反：{@code completeAuthContext} 最后一行是
 * {@code return targetType == Tenant}，{@code AgentModel} 落到 {@code return false} ⇒
 * {@code apiKeyMissing}/{@code apiKeyAccessDenied}。也就是说<b>这种 Key 打不进任何 Spring MVC 端点</b>，
 * 只能被独立的模型代理（Netty，{@code model-api-proxy.port}，默认 18086）接受 —— 正是我们要的范围。
 *
 * <h2>★调用方式（需求里的「通过 APIKEY 进行模型调用」）</h2>
 *
 * 模型代理<b>不是</b> Spring MVC 服务，因此本门面不代理模型流量，只负责签发凭据并告知地址：
 * <pre>
 *   POST {baseUrl}/{modelId}/v1/chat/completions
 *   Authorization: Bearer &lt;accessKey&gt;      （或 x-api-key: &lt;accessKey&gt;）
 * </pre>
 * {@code baseUrl} = {@code siteUrl + "/api/proxy/model"}，创建响应里直接给出。
 * 代理侧会自行校验：Key 的 {@code modelIds} 含该 modelId、该用户确有此模型权限、
 * 模型 {@code usageScenarios} 含 {@code OpenApi}，并做 token 限额与订阅计价。
 * 上游真实 baseUrl / apiKey 由代理在请求时解析，<b>绝不</b>经过本门面。
 *
 * <h2>★脱敏</h2>
 *
 * 模型列表<b>不返回</b> {@code ModelConfigDto} 原对象，而是逐字段挑出白名单构造 {@link ModelItemVo}：
 * {@code apiInfoList}（含上游 key）虽然在 {@code queryModelConfigList} 返回前已被置 null，
 * {@code natInfoList} 却只是 {@code @Schema(hidden)}（仅影响文档，不影响序列化）。
 * 门面是对外的最终出口，不能依赖上游某个 impl 的置空行为恰好没变——白名单构造是唯一稳的做法。
 */
@Tag(name = "OAuth2 门面-模型", description = "需 OAuth2 access_token（oa- 前缀）；model:* 仅限授权码模式")
@RestController
@RequestMapping("/api/oauth2/model")
@Slf4j
public class OAuth2ModelFacadeController {

    /**
     * 本门面签发的 Key 统一用这个 targetId，与平台内部 AgentModel Key 的
     * {@code "{agentId}-{conversationId}"} 形态区隔开：
     * list / update / revoke 都按 {@code (userId, AgentModel, OAUTH2_TARGET_ID)} 精确查，
     * 因此第三方<b>看不到也动不了</b>平台内部为沙箱签发的模型 Key。
     */
    private static final String OAUTH2_TARGET_ID = "oauth2-model";

    /**
     * 单个用户经本门面持有的 Key 上限。
     * <p>
     * 平台既有的 Key 创建入口也没有数量限制，但那些是站内用户手动点的；
     * 门面是第三方程序化调用，不设上限就等于开放一个「无限签发可计费凭据」的接口。
     * 超限应吊销不再使用的 Key，而不是继续申请。
     */
    private static final int MAX_KEYS_PER_USER = 20;

    /** 模型代理路径前缀，与 {@code ModelApiProxyConfigServiceImpl} 生成的前端 baseUrl 保持一致 */
    private static final String PROXY_PATH = "/api/proxy/model";

    @Resource
    private ModelApplicationService modelApplicationService;

    @Resource
    private IModelRpcService modelRpcService;

    @Resource
    private UserAccessKeyApiService userAccessKeyApiService;

    @Resource
    private TenantConfigApplicationService tenantConfigApplicationService;

    /** 与模型代理同源的地址覆盖项；为空时回落租户 siteUrl */
    @Value("${model-api-proxy.base-api-url:}")
    private String baseApiUrl;

    // ================================================================== model:read

    /**
     * 查询授权用户<b>可用于 API 调用</b>的模型。
     * <p>
     * 口径与模型代理的准入判定完全对齐，因此这里列出的就是「能签进 Key 且调用不会被拒」的集合：
     * <ol>
     * <li>先取 {@code getUserModels(tenantId, userId)} —— 用户<b>有权访问</b>的模型
     * （含租户级与所属空间级），而不是全租户模型；</li>
     * <li>再按 {@code usageScenarios} 含 {@code OpenApi} 过滤 —— 未开放外部调用的模型
     * 即便用户能在界面上用，代理也会以 {@code 4030 The model does not support external calls} 拒绝；</li>
     * <li>再按 {@code enabled == 1} 过滤 —— 停用的模型不可用。</li>
     * </ol>
     * 因此本列表<b>可能少于</b>用户在界面上看到的模型数，这是有意为之，不是漏数据。
     */
    @Operation(summary = "查询授权用户可用于 API 调用的模型列表")
    @GetMapping("/list")
    public ReqResult<List<ModelItemVo>> modelList() {
        Long tenantId = RequestContext.get().getTenantId();
        Long userId = RequestContext.get().getUserId();
        List<ModelInfoDto> userModels = modelRpcService.getUserModels(tenantId, userId);
        if (CollectionUtils.isEmpty(userModels)) {
            return ReqResult.success(List.of());
        }
        List<Long> modelIds = userModels.stream().map(ModelInfoDto::getId).filter(java.util.Objects::nonNull).distinct()
                .toList();
        if (modelIds.isEmpty()) {
            return ReqResult.success(List.of());
        }
        // queryModelConfigListByIds 走列表口径，返回前已置空 apiInfoList；这里再白名单构造一次，双保险
        List<ModelConfigDto> configs = modelApplicationService.queryModelConfigListByIds(modelIds);
        if (CollectionUtils.isEmpty(configs)) {
            return ReqResult.success(List.of());
        }
        List<ModelItemVo> items = new ArrayList<>();
        for (ModelConfigDto config : configs) {
            if (!isCallableByApiKey(config)) {
                continue;
            }
            items.add(new ModelItemVo(config.getId(), config.getName(), config.getModel(), config.getProviderName(),
                    enumName(config.getType()), enumName(config.getApiProtocol()), config.getDescription(),
                    config.getTag(), config.getMaxTokens(), config.getMaxContextTokens(),
                    enumName(config.getFunctionCall()), config.getIsReasonModel()));
        }
        return ReqResult.success(items);
    }

    // ================================================================== model:key

    /**
     * 创建模型 API Key 并授权可用模型。
     * <p>
     * ★{@code accessKey} 明文<b>只在本响应里出现一次</b>，之后的 list 只返回掩码；
     * 平台不存明文副本可供再次查看（与既有 AK 口径一致），丢了只能吊销重建。
     * <p>
     * ★{@code modelIds} 必填且会<b>逐个校验</b>是否在 {@link #modelList()} 的集合内：
     * 不校验的话第三方可以塞任意 id，签出一把「看起来授权了、调用时才 4030」的 Key，
     * 排查成本转嫁给调用方。
     * <p>
     * ⚠️ <b>过期时间对这种 Key 不生效</b>：模型代理的准入判定
     * （{@code ModelApiProxyConfigServiceImpl.getBackendModelConfig}）只看 targetType、
     * {@code config.modelIds}、用户模型权限与 {@code usageScenarios}，<b>不校验</b> {@code expire} 与
     * {@code status}。所以<b>吊销是唯一有效的关闭手段</b>，不要指望设个到期日就自动失效。
     */
    @Operation(summary = "创建模型 API Key 并授权模型（明文只返回一次）")
    @PostMapping("/api-key/create")
    public ReqResult<ApiKeyCreatedVo> createApiKey(@RequestBody ModelApiKeyRequest request) {
        Long userId = RequestContext.get().getUserId();
        List<Long> modelIds = normalizeModelIds(request == null ? null : request.getModelIds());
        List<Long> owned = requireOwnedModelIds(modelIds);
        List<UserAccessKeyDto> existing = facadeKeys(userId);
        if (existing.size() >= MAX_KEYS_PER_USER) {
            return ReqResult.error("Too many API keys (limit " + MAX_KEYS_PER_USER + "), revoke unused ones first");
        }
        UserAccessKeyDto created = userAccessKeyApiService.newAccessKey(RequestContext.get().getTenantId(), userId,
                UserAccessKeyDto.AKTargetType.AgentModel, OAUTH2_TARGET_ID,
                UserAccessKeyDto.UserAccessKeyConfig.builder()
                        // ★只放 modelIds + enabled：上游 baseUrl / apiKey 由模型代理在调用时自行解析，
                        // 绝不在签发阶段落到 user_access_key.config 里（那等于把上游凭据存了一份可被读走的副本）
                        .modelIds(new ArrayList<>(owned))
                        .enabled(true)
                        .build());
        if (created == null || StringUtils.isBlank(created.getAccessKey())) {
            return ReqResult.error("Failed to create API key");
        }
        String baseUrl = proxyBaseUrl();
        log.info("OAuth2 model API key created, userId={}, keyId={}, modelCount={}", userId, created.getId(),
                owned.size());
        return ReqResult.success(new ApiKeyCreatedVo(created.getId(), created.getAccessKey(), baseUrl, owned,
                usageHint(baseUrl)));
    }

    /**
     * 查询本门面签发的 Key 列表（{@code accessKey} 掩码）。
     * <p>
     * 只返回 {@code targetId = oauth2-model} 的 Key：平台内部为沙箱签发的 AgentModel Key
     * 不在此列，第三方既看不到也动不了。
     */
    @Operation(summary = "查询本门面签发的模型 API Key 列表（掩码）")
    @GetMapping("/api-key/list")
    public ReqResult<List<ApiKeyItemVo>> listApiKeys() {
        List<ApiKeyItemVo> items = facadeKeys(RequestContext.get().getUserId()).stream()
                .map(key -> new ApiKeyItemVo(key.getId(), mask(key.getAccessKey()),
                        key.getConfig() == null ? List.of() : nullToEmpty(key.getConfig().getModelIds()),
                        key.getConfig() == null || !Boolean.FALSE.equals(key.getConfig().getEnabled()),
                        key.getCreated(), key.getExpire()))
                .toList();
        return ReqResult.success(items);
    }

    /**
     * 修改 Key 的模型授权范围与启用状态。
     * <p>
     * ★只支持改 {@code modelIds} / {@code enabled}：{@code newAccessKey} 与
     * {@code updateUserAccessKeyConfig} 都不落 {@code name}，
     * 而 {@code UserApiKeyApplicationService.updateUserApiKey} 面向的是 {@code OpenApi} 型 Key
     * （会连带重写 {@code apiConfigs}），拿来改 AgentModel Key 属于误用。
     * 需要换名字 / 换到期语义时请吊销后重建。
     * <p>
     * ⚠️ {@code enabled=false} 同样<b>不被模型代理校验</b>（见 {@link #createApiKey} 的说明），
     * 要真正停用请调 {@link #revokeApiKey}。这里保留该字段是为了与平台 Key 模型一致，
     * 但<b>不要</b>把它当成安全开关。
     */
    @Operation(summary = "修改模型 API Key 的授权模型范围")
    @PostMapping("/api-key/update")
    public ReqResult<ApiKeyItemVo> updateApiKey(@RequestBody ModelApiKeyUpdateRequest request) {
        if (request == null || request.getId() == null) {
            return ReqResult.error("id is required");
        }
        Long userId = RequestContext.get().getUserId();
        UserAccessKeyDto key = findFacadeKey(userId, request.getId());
        if (key == null) {
            return ReqResult.error("API key not found");
        }
        List<Long> modelIds = normalizeModelIds(request.getModelIds());
        List<Long> owned = requireOwnedModelIds(modelIds);
        UserAccessKeyDto.UserAccessKeyConfig config = key.getConfig() == null
                ? UserAccessKeyDto.UserAccessKeyConfig.builder().build()
                : key.getConfig();
        config.setModelIds(new ArrayList<>(owned));
        if (request.getEnabled() != null) {
            config.setEnabled(request.getEnabled());
        }
        userAccessKeyApiService.updateUserAccessKeyConfig(key.getId(), config);
        log.info("OAuth2 model API key updated, userId={}, keyId={}, modelCount={}", userId, key.getId(), owned.size());
        return ReqResult.success(new ApiKeyItemVo(key.getId(), mask(key.getAccessKey()), owned,
                request.getEnabled() == null || request.getEnabled(), key.getCreated(), key.getExpire()));
    }

    /**
     * 吊销 Key（物理删除，<b>不可恢复</b>）。
     * <p>
     * ★这是关闭一把已外泄 Key 的<b>唯一</b>有效手段：如前所述代理不校验 expire / status。
     * 删除后代理侧的 1 分钟结果缓存（{@code backendModelConfig}）仍可能让旧 Key 短暂可用，
     * 需要立即生效时应同时清理该缓存。
     */
    @Operation(summary = "吊销模型 API Key（物理删除，不可恢复）")
    @PostMapping("/api-key/revoke")
    public ReqResult<Void> revokeApiKey(@RequestBody ModelApiKeyRevokeRequest request) {
        if (request == null || request.getId() == null) {
            return ReqResult.error("id is required");
        }
        Long userId = RequestContext.get().getUserId();
        UserAccessKeyDto key = findFacadeKey(userId, request.getId());
        if (key == null) {
            return ReqResult.error("API key not found");
        }
        userAccessKeyApiService.deleteAccessKey(userId, key.getAccessKey());
        log.info("OAuth2 model API key revoked, userId={}, keyId={}", userId, key.getId());
        return ReqResult.success();
    }

    // ================================================================== 出入参

    /**
     * 可用模型项（白名单字段，不含任何上游凭据）
     *
     * @param id               模型 ID，签发 Key 时用它授权，调用时拼进代理路径
     * @param name             模型展示名
     * @param model            模型标识（上游的 model 参数值）
     * @param providerName     供应商名
     * @param type             模型类型（Completions / Chat / Embeddings / …）
     * @param apiProtocol      接口协议（OpenAI / Ollama / …），决定代理路径下用哪套兼容接口
     * @param description      描述
     * @param tag              标签
     * @param maxTokens        输出 token 上限
     * @param maxContextTokens 上下文 token 上限
     * @param functionCall     函数调用支持程度
     * @param isReasonModel    是否推理模型
     */
    public record ModelItemVo(Long id, String name, String model, String providerName, String type,
                              String apiProtocol, String description, String tag, Integer maxTokens,
                              Integer maxContextTokens, String functionCall, Integer isReasonModel) {
    }

    /**
     * 创建结果
     *
     * @param id        Key 主键，后续 update / revoke 用它
     * @param accessKey ★明文，只出现这一次
     * @param baseUrl   模型代理地址（{@code siteUrl + /api/proxy/model}）
     * @param modelIds  已授权的模型 ID
     * @param usage     调用示例路径
     */
    public record ApiKeyCreatedVo(Long id, String accessKey, String baseUrl, List<Long> modelIds, String usage) {
    }

    /**
     * Key 列表项
     *
     * @param maskedKey 掩码后的 Key（{@code ak-****} + 末 4 位），仅用于人工辨认
     */
    public record ApiKeyItemVo(Long id, String maskedKey, List<Long> modelIds, Boolean enabled, Date created,
                               Date expire) {
    }

    /** 创建 Key 入参 */
    @Data
    @Schema(description = "创建模型 API Key 入参")
    public static class ModelApiKeyRequest {

        @Schema(description = "要授权的模型 ID 列表，必填；取值范围见 GET /api/oauth2/model/list")
        private List<Long> modelIds;
    }

    /** 修改 Key 入参 */
    @Data
    @Schema(description = "修改模型 API Key 入参")
    public static class ModelApiKeyUpdateRequest {

        @Schema(description = "Key 主键，必填")
        private Long id;

        @Schema(description = "新的授权模型 ID 列表，必填（整体替换，不是增量）")
        private List<Long> modelIds;

        @Schema(description = "是否启用；⚠️ 模型代理不校验该字段，停用请用 revoke")
        private Boolean enabled;
    }

    /** 吊销 Key 入参 */
    @Data
    @Schema(description = "吊销模型 API Key 入参")
    public static class ModelApiKeyRevokeRequest {

        @Schema(description = "Key 主键，必填")
        private Long id;
    }

    // ================================================================== 私有辅助

    /**
     * 该模型是否能被 API Key 调用：与 {@code getBackendModelConfig} 的准入判定同源
     * （{@code usageScenarios} 含 {@code OpenApi} 且已启用）。
     */
    private static boolean isCallableByApiKey(ModelConfigDto config) {
        if (config == null || config.getId() == null) {
            return false;
        }
        if (config.getEnabled() == null || config.getEnabled() != 1) {
            return false;
        }
        List<UsageScenarioEnum> scenarios = config.getUsageScenarios();
        return scenarios != null && scenarios.contains(UsageScenarioEnum.OpenApi);
    }

    /** 去空、去重、保序；空结果由调用方判错 */
    private static List<Long> normalizeModelIds(List<Long> modelIds) {
        if (CollectionUtils.isEmpty(modelIds)) {
            return List.of();
        }
        Set<Long> distinct = new LinkedHashSet<>();
        for (Long id : modelIds) {
            if (id != null) {
                distinct.add(id);
            }
        }
        return new ArrayList<>(distinct);
    }

    /**
     * 校验待授权的模型确实都在「该用户可用」集合内，返回归一化后的 id 列表。
     * <p>
     * 复用 {@link #isCallableByApiKey}，避免这里的口径与 {@code /model/list} 漂移
     * ——两处一旦不一致，就会出现「列表里没有、却能签进 Key」或反过来的怪事。
     */
    private List<Long> requireOwnedModelIds(List<Long> modelIds) {
        if (modelIds.isEmpty()) {
            throw new IllegalArgumentException("modelIds is required");
        }
        List<ModelInfoDto> userModels = modelRpcService.getUserModels(RequestContext.get().getTenantId(),
                RequestContext.get().getUserId());
        Set<Long> owned = userModels == null ? Set.of()
                : userModels.stream().map(ModelInfoDto::getId).filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.toSet());
        List<ModelConfigDto> configs = modelApplicationService.queryModelConfigListByIds(modelIds);
        Set<Long> callable = configs == null ? Set.of()
                : configs.stream().filter(OAuth2ModelFacadeController::isCallableByApiKey).map(ModelConfigDto::getId)
                        .collect(java.util.stream.Collectors.toSet());
        List<Long> rejected = modelIds.stream().filter(id -> !owned.contains(id) || !callable.contains(id)).toList();
        if (!rejected.isEmpty()) {
            // 只回 id 不回原因：区分「不存在」/「无权」/「未开放外部调用」会让本端点变成模型枚举探测器
            throw new IllegalArgumentException("model not available for API access: " + rejected);
        }
        return modelIds;
    }

    /** 本门面签发的 Key（按 userId + AgentModel + 固定 targetId 精确圈定） */
    private List<UserAccessKeyDto> facadeKeys(Long userId) {
        List<UserAccessKeyDto> keys = userAccessKeyApiService.queryAccessKeyList(userId,
                UserAccessKeyDto.AKTargetType.AgentModel, OAUTH2_TARGET_ID);
        return keys == null ? List.of() : keys;
    }

    /**
     * 按主键取一把<b>属于当前授权用户、且由本门面签发</b>的 Key；取不到返回 null。
     * <p>
     * 刻意不用「按 id 直接查库」：那会允许第三方传别人的 Key id 来改授权或吊销。
     */
    private UserAccessKeyDto findFacadeKey(Long userId, Long id) {
        return facadeKeys(userId).stream().filter(key -> id.equals(key.getId())).findFirst().orElse(null);
    }

    /**
     * 掩码：保留前缀与末 4 位，够人工辨认是哪一把，又不足以还原。
     */
    private static String mask(String accessKey) {
        if (StringUtils.isBlank(accessKey)) {
            return null;
        }
        if (accessKey.length() <= 7) {
            return "****";
        }
        return accessKey.substring(0, 3) + "****" + accessKey.substring(accessKey.length() - 4);
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    /**
     * 模型代理地址：优先 {@code model-api-proxy.base-api-url}，为空回落租户 {@code siteUrl}
     * （与 {@code ModelApiProxyConfigServiceImpl.generateUserFrontendModelConfig} 同序，
     * 保证告知第三方的地址与站内前端拿到的一致）。
     * 两者都取不到时返回 null —— 此时 Key 仍然可用，只是调用方需自行知道代理地址。
     */
    private String proxyBaseUrl() {
        String siteUrl = StringUtils.trimToNull(baseApiUrl);
        if (siteUrl == null) {
            TenantConfigDto tenantConfig = tenantConfigApplicationService
                    .getTenantConfig(RequestContext.get().getTenantId());
            siteUrl = tenantConfig == null ? null : StringUtils.trimToNull(tenantConfig.getSiteUrl());
        }
        if (siteUrl == null) {
            return null;
        }
        return (siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl) + PROXY_PATH;
    }

    /** 调用示例：把「怎么用这把 Key」随创建响应一起给出，省得第三方再翻文档 */
    private static String usageHint(String baseUrl) {
        String base = baseUrl == null ? "{baseUrl}" : baseUrl;
        return "POST " + base + "/{modelId}/v1/chat/completions  with header  Authorization: Bearer <accessKey>";
    }
}
