package com.xspaceagi.eco.market.domain.client;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xspaceagi.agent.core.adapter.application.ModelApplicationService;
import com.xspaceagi.agent.core.adapter.dto.config.ModelConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.ModelConfig;
import com.xspaceagi.agent.core.spec.enums.*;
import com.xspaceagi.eco.market.domain.config.EcoMarketConfig;
import com.xspaceagi.eco.market.domain.dto.req.*;
import com.xspaceagi.eco.market.domain.dto.resp.ServerConfigDetailRespDTO;
import com.xspaceagi.eco.market.domain.dto.resp.ServerConfigListRespDTO;
import com.xspaceagi.eco.market.sdk.model.ClientSecretDTO;
import com.xspaceagi.eco.market.spec.constant.EcoMarketApiConstant;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.EcoMarketException;
import com.xspaceagi.system.spec.page.PageQueryVo;
import com.xspaceagi.system.spec.page.SuperPage;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import com.xspaceagi.system.spec.utils.RedisUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 生态市场服务器API调用服务
 * 封装客户端对服务器端API的调用
 */
@Slf4j
@Service
@DependsOn({"ecoMarketConfig", "ecoMarketProperties"})
public class EcoMarketServerApiService {

    private final OkHttpClient httpClient;
    private String serverBaseUrl;
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    @Resource
    private ModelApplicationService modelApplicationService;

    @Resource
    private RedisUtil redisUtil;

    @Resource
    private EcoMarketConfig ecoMarketConfig;

    public EcoMarketServerApiService() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();

        log.info("Init eco-market server API client (Fastjson2 JSON)");
    }

    /**
     * 初始化方法，在Spring容器完全初始化Bean后调用
     */
    @PostConstruct
    public void init() {
        try {
            this.serverBaseUrl = ecoMarketConfig.getServerBaseUrl();
            log.info("Init eco-market server API client, URL: {}", this.serverBaseUrl);
        } catch (Exception e) {
            log.warn("Eco-market config init error, will retry on first use: {}", e.getMessage());
        }
    }

    /**
     * 构建API URL
     *
     * @param apiPath API路径
     * @return 完整的API URL
     */
    private String buildApiUrl(String apiPath) {
        // 如果初始化时未能获取URL，这里再次尝试
        if (serverBaseUrl == null) {
            try {
                serverBaseUrl = ecoMarketConfig.getServerBaseUrl();
                log.info("Lazy init eco-market server API URL: {}", serverBaseUrl);
            } catch (Exception e) {
                log.error("Get server base URL failed", e);
                throw new RuntimeException("无法获取生态市场服务器基础URL，请检查eco-market.server.base-url配置", e);
            }
        }

        String baseUrl = serverBaseUrl;
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (apiPath.startsWith("/")) {
            apiPath = apiPath.substring(1);
        }
        return baseUrl + "/" + apiPath;
    }

    /**
     * 注册客户端
     *
     * @param name        客户端名称
     * @param description 客户端描述
     * @param tenantId    租户ID
     * @return 注册结果，包含客户端ID和密钥
     */
    public ClientSecretDTO registerClient(String name, String description, Long tenantId, String clientId) {
        try {
            String url = buildApiUrl(EcoMarketApiConstant.SERVER_API_BASE + "/secret/register");

            // 构建请求DTO
            ClientRegisterReqDTO reqDTO = new ClientRegisterReqDTO();
            reqDTO.setName(name);
            reqDTO.setDescription(description);
            reqDTO.setTenantId(tenantId);
            reqDTO.setClientId(clientId);

            // 构建请求体
            String jsonBody = JSON.toJSONString(reqDTO);
            RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);

            // 构建请求
            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .build();

            // 发送请求
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("Client register request failed, status: {}", response.code());
                    throw EcoMarketException.build(BizExceptionCodeEnum.configNotFound,
                            "请求失败，状态码: " + response.code());
                }

                String responseBody = null;
                if (response.body() != null) {
                    responseBody = response.body().string();
                }
                ReqResult<ClientSecretDTO> result = JSON.parseObject(responseBody,
                        new TypeReference<ReqResult<ClientSecretDTO>>() {
                        });

                if (result != null && result.isSuccess()) {
                    // 保存默认会话模型配置
                    modelApplicationService.addOrUpdate(buildModelConfig(tenantId, ModelTypeEnum.Chat, 1L));
                    // 保存默认嵌入模型配置
                    modelApplicationService.addOrUpdate(buildModelConfig(tenantId, ModelTypeEnum.Embeddings, 2L));
                    redisUtil.leftPush("tenant_created", tenantId);

                    // todo

                    return result.getData();
                } else {
                    log.error("Client register failed: {}", result != null ? result.getMessage() : "Unknown error");
                    throw EcoMarketException.build(BizExceptionCodeEnum.configNotFound,
                            result != null ? result.getMessage() : "未知错误");
                }
            }
        } catch (IOException e) {
            log.error("Client register API error", e);
            throw EcoMarketException.build(BizExceptionCodeEnum.configNotFound, e.getMessage());
        }
    }

    private ModelConfigDto buildModelConfig(Long tenantId, ModelTypeEnum type, Long id) {
        ModelConfigDto modelConfigDto = new ModelConfigDto();
        modelConfigDto.setPid("nuwax");
        ModelConfigDto modelConfigDto1 = TenantFunctions
                .callWithIgnoreCheck(() -> modelApplicationService.queryModelConfigById(id));
        if (modelConfigDto1 == null) {
            modelConfigDto.setId(id);
        }
        modelConfigDto.setSpaceId(-1L);
        modelConfigDto.setIsReasonModel(YesOrNoEnum.N.getKey());
        modelConfigDto.setScope(ModelConfig.ModelScopeEnum.Tenant);
        modelConfigDto.setCreatorId(-1L);
        modelConfigDto.setTenantId(tenantId);
        String baseUrl = "https://api.nuwax.com";
        if (type == ModelTypeEnum.Chat) {
            modelConfigDto.setName("DeepSeek V4 Flash");
            modelConfigDto.setModel("deepseek-v4-flash");
            modelConfigDto.setType(ModelTypeEnum.Chat);
            modelConfigDto.setTypes(List.of(ModelCapabilityEnum.Text, ModelCapabilityEnum.Reasoning));
            modelConfigDto.setIsReasonModel(YesOrNoEnum.Y.getKey());
            modelConfigDto.setApiProtocol(ModelApiProtocolEnum.Anthropic);
            modelConfigDto.setUsageScenarios(List.of(UsageScenarioEnum.OpenApi, UsageScenarioEnum.ChatBot, UsageScenarioEnum.PageApp, UsageScenarioEnum.TaskAgent, UsageScenarioEnum.Workflow));
            baseUrl = "https://api.nuwax.com/anthropic";
            modelConfigDto.setFunctionCall(ModelFunctionCallEnum.StreamCallSupported);
            modelConfigDto.setDescription("Cost-Effective Choice: 284B total parameters, 13B activated, with native million-token context support. Generation speed increased by 50%, invocation cost only 1/12th of Pro, with second-level response and no lag. Ideal for chat interfaces, batch document processing, and lightweight agents, offering a money-saving and efficient solution for large-scale business integration.");
            modelConfigDto.setMaxTokens(64000);
            modelConfigDto.setMaxContextTokens(980000);
        } else if (type == ModelTypeEnum.Embeddings) {
            modelConfigDto.setName("text-embedding-v4");
            modelConfigDto.setModel("text-embedding-v4");
            modelConfigDto.setApiProtocol(ModelApiProtocolEnum.OpenAI);
            modelConfigDto.setType(ModelTypeEnum.Embeddings);
            modelConfigDto.setFunctionCall(ModelFunctionCallEnum.Unsupported);
            modelConfigDto.setDescription("The Universal Text Embedding V4, a multilingual unified text embedding model trained by Tongyi Lab based on Qwen3, delivers significant improvements in text retrieval, clustering, and classification performance compared to V3. It achieves a 15%–40% performance gain on evaluation tasks such as MTEB multilingual, Chinese-English, and Code retrieval. It supports user-customizable vector dimensions ranging from 64 to 2048.");
            modelConfigDto.setMaxTokens(8192);
        }
        modelConfigDto.setStrategy(ModelConfig.ModelStrategyEnum.RoundRobin);
        modelConfigDto.setTopP(0.7);
        modelConfigDto.setTemperature(1.0);
        modelConfigDto.setNetworkType(ModelConfig.NetworkType.Internet);
        modelConfigDto.setDimension(2048);
        ModelConfigDto.ApiInfo apiInfo = new ModelConfigDto.ApiInfo();
        apiInfo.setUrl(baseUrl);
        apiInfo.setKey("ak-TENANT_SECRET");
        apiInfo.setWeight(1);
        modelConfigDto.setApiInfoList(List.of(apiInfo));
        return modelConfigDto;
    }

    /**
     * 获取服务器配置详情
     *
     * @param uid          配置唯一标识
     * @param clientId     客户端ID
     * @param clientSecret 客户端密钥
     * @return 配置详情，失败时返回null
     */
    public ServerConfigDetailRespDTO getServerConfigDetail(String uid, String clientId, String clientSecret) {
        try {
            String url = buildApiUrl(EcoMarketApiConstant.SERVER_API_BASE + "/config/detail");

            // 构建请求DTO
            ServerConfigDetailReqDTO reqDTO = new ServerConfigDetailReqDTO();
            reqDTO.setUid(uid);
            reqDTO.setClientId(clientId);
            reqDTO.setClientSecret(clientSecret);

            // 构建请求体
            String jsonBody = JSON.toJSONString(reqDTO);
            RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);

            // 构建请求
            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .build();

            // 发送POST请求
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("Get server config detail request failed, status: {}", response.code());
                    throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketGetConfigFailed,
                            "请求失败，状态码: " + response.code());
                }

                String responseBody = response.body().string();
                ReqResult<ServerConfigDetailRespDTO> result = JSON.parseObject(responseBody,
                        new TypeReference<ReqResult<ServerConfigDetailRespDTO>>() {
                        });

                if (result != null && result.isSuccess()) {
                    return result.getData();
                } else {
                    log.error("Failed to get server config detail: {}", result != null ? result.getMessage() : "Unknown error");
                    throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketGetConfigFailed,
                            result != null ? result.getMessage() : "未知错误");
                }
            }
        } catch (IOException e) {
            log.error("Server config detail API error", e);
            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketGetConfigFailed, e.getMessage());
        }
    }

    /**
     * 批量获取服务器配置详情
     *
     * @param uids         配置唯一标识列表
     * @param clientId     客户端ID
     * @param clientSecret 客户端密钥
     * @return 配置详情列表，失败时抛出异常
     */
    public List<ServerConfigDetailRespDTO> getBatchServerConfigDetail(List<String> uids, String clientId,
                                                                      String clientSecret) {
        try {
            String url = buildApiUrl(EcoMarketApiConstant.SERVER_API_BASE + "/config/batchDetail");

            // 构建请求DTO
            ServerConfigBatchDetailReqDTO reqDTO = new ServerConfigBatchDetailReqDTO();
            reqDTO.setUids(uids);
            reqDTO.setClientId(clientId);
            reqDTO.setClientSecret(clientSecret);

            // 构建请求体
            String jsonBody = JSON.toJSONString(reqDTO);
            RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);

            // 构建请求
            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .build();

            // 发送POST请求
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("Batch get server config details request failed, status: {}", response.code());
                    throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketGetConfigFailed,
                            "请求失败，状态码: " + response.code());
                }

                String responseBody = response.body().string();
                ReqResult<List<ServerConfigDetailRespDTO>> result = JSON.parseObject(responseBody,
                        new TypeReference<ReqResult<List<ServerConfigDetailRespDTO>>>() {
                        });

                if (result != null && result.isSuccess()) {
                    return result.getData();
                } else {
                    log.error("Batch get server config details failed: {}", result != null ? result.getMessage() : "Unknown error");
                    throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketGetConfigFailed,
                            result != null ? result.getMessage() : "未知错误");
                }
            }
        } catch (IOException e) {
            log.error("Exception calling batch server config detail API", e);
            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketGetConfigFailed, e.getMessage());
        }
    }

    /**
     * 批量获取服务器<b>审批</b>详情
     *
     * @param uids         配置唯一标识列表
     * @param clientId     客户端ID
     * @param clientSecret 客户端密钥
     * @return 配置详情列表，失败时抛出异常
     */
    public List<ServerConfigDetailRespDTO> getBatchServerApproveDetail(List<String> uids, String clientId,
                                                                       String clientSecret) {
        try {
            String url = buildApiUrl(EcoMarketApiConstant.SERVER_API_BASE + "/config/batchApproveDetail");

            // 构建请求DTO
            ServerConfigBatchDetailReqDTO reqDTO = new ServerConfigBatchDetailReqDTO();
            reqDTO.setUids(uids);
            reqDTO.setClientId(clientId);
            reqDTO.setClientSecret(clientSecret);

            // 构建请求体
            String jsonBody = JSON.toJSONString(reqDTO);
            RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);

            // 构建请求
            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .build();

            // 发送POST请求
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("Batch get server config details request failed, status: {}", response.code());
                    throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketGetConfigFailed,
                            "请求失败，状态码: " + response.code());
                }

                String responseBody = response.body().string();
                ReqResult<List<ServerConfigDetailRespDTO>> result = JSON.parseObject(responseBody,
                        new TypeReference<ReqResult<List<ServerConfigDetailRespDTO>>>() {
                        });

                if (result != null && result.isSuccess()) {
                    return result.getData();
                } else {
                    log.error("Batch get server config details failed: {}", result != null ? result.getMessage() : "Unknown error");
                    throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketGetConfigFailed,
                            result != null ? result.getMessage() : "未知错误");
                }
            }
        } catch (IOException e) {
            log.error("Exception calling batch server config detail API", e);
            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketGetConfigFailed, e.getMessage());
        }
    }

    /**
     * 保存服务器配置
     *
     * @param reqDTO 保存配置请求DTO
     * @return 保存结果，失败时返回null
     */
    public <T> T saveServerConfig(ServerConfigSaveReqDTO reqDTO, Class<T> responseType) {
        try {
            String url = buildApiUrl(EcoMarketApiConstant.SERVER_API_BASE + "/config/save");

            // 构建请求体
            String jsonBody = JSON.toJSONString(reqDTO);
            RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);

            // 构建请求
            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .build();

            // 发送请求
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("Save server config request failed, status: {}", response.code());
                    throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketPublishConfigFailed,
                            "请求失败，状态码: " + response.code());
                }

                String responseBody = response.body().string();
                ReqResult<Object> result = JSON.parseObject(responseBody,
                        new TypeReference<ReqResult<Object>>() {
                        });

                if (result != null && result.isSuccess()) {
                    // 将结果转换为指定类型
                    return JSON.parseObject(JSON.toJSONString(result.getData()), responseType);
                } else {
                    log.error("Save server config failed: {}", result != null ? result.getMessage() : "Unknown error");
                    throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketPublishConfigFailed,
                            result != null ? result.getMessage() : "未知错误");
                }
            }
        } catch (IOException e) {
            log.error("Server save config API error", e);
            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketPublishConfigFailed, e.getMessage());
        }
    }

    /**
     * 下线服务器配置
     * 服务器端接口对应 EcoMarketServerConfigController 中的 offline 方法
     *
     * @param uid          配置UID
     * @param clientId     客户端ID
     * @param clientSecret 客户端密钥
     * @return 操作成功返回true，失败返回false
     */
    public boolean offlineServerConfig(String uid, String clientId, String clientSecret) {
        try {
            String url = buildApiUrl(EcoMarketApiConstant.SERVER_API_BASE + "/config/offline");

            // 构建请求体使用DTO对象
            ServerConfigOfflineReqDTO reqDTO = new ServerConfigOfflineReqDTO();
            reqDTO.setUid(uid);
            reqDTO.setClientId(clientId);
            reqDTO.setClientSecret(clientSecret);

            // 将DTO对象转换为JSON
            String jsonBody = JSON.toJSONString(reqDTO);
            RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);

            // 构建请求
            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .build();

            // 发送请求
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("Offline server config request failed, status: {}", response.code());
                    throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketOfflineConfigFailed,
                            "请求失败，状态码: " + response.code());
                }

                var responseBodyObj = response.body();
                if (responseBodyObj == null) {
                    throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketConfigResultBodyEmpty);
                }
                String responseBody = responseBodyObj.string();
                ReqResult<Void> result = JSON.parseObject(responseBody,
                        new TypeReference<ReqResult<Void>>() {
                        });

                // 判断请求是否成功
                return result != null && result.isSuccess();
            }
        } catch (IOException e) {
            log.error("Server offline config API error", e);
            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketOfflineConfigFailed, e.getMessage());
        }
    }

    /**
     * 撤销发布服务器配置
     * 服务器端接口对应 EcoMarketServerConfigController 中的 offline 方法
     *
     * @param uid          配置UID
     * @param clientId     客户端ID
     * @param clientSecret 客户端密钥
     * @return 操作成功返回true，失败返回false
     */
    public boolean unpublishServerConfig(String uid, String clientId, String clientSecret) {
        try {
            String url = buildApiUrl(EcoMarketApiConstant.SERVER_API_BASE + "/config/unpublish");

            // 构建请求体使用DTO对象
            ServerConfigUnpublishReqDTO reqDTO = new ServerConfigUnpublishReqDTO();
            reqDTO.setUid(uid);
            reqDTO.setClientId(clientId);
            reqDTO.setClientSecret(clientSecret);

            // 将DTO对象转换为JSON
            String jsonBody = JSON.toJSONString(reqDTO);
            RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);

            // 构建请求
            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .build();

            // 发送请求
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("Revoke publish server config request failed, status: {}", response.code());
                    throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketOfflineConfigFailed,
                            "请求失败，状态码: " + response.code());
                }

                var responseBodyObj = response.body();
                if (responseBodyObj == null) {
                    throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketConfigResultBodyEmpty);
                }
                String responseBody = responseBodyObj.string();
                ReqResult<Void> result = JSON.parseObject(responseBody,
                        new TypeReference<ReqResult<Void>>() {
                        });

                return result != null && result.isSuccess();
            }
        } catch (IOException e) {
            log.error("Server revoke publish API error", e);
            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketOfflineConfigFailed, e.getMessage());
        }
    }

    /**
     * 查询配置状态
     *
     * @param uid          配置UID
     * @param clientId     客户端ID
     * @param clientSecret 客户端密钥
     * @return 状态信息，失败时返回null
     */
    public Map<String, Object> getConfigStatus(String uid, String clientId, String clientSecret) {
        try {
            String url = buildApiUrl(EcoMarketApiConstant.SERVER_API_BASE + "/config/status");

            // 构建请求DTO
            ServerConfigStatusReqDTO reqDTO = new ServerConfigStatusReqDTO();
            reqDTO.setUid(uid);
            reqDTO.setClientId(clientId);
            reqDTO.setClientSecret(clientSecret);

            // 构建请求体
            String jsonBody = JSON.toJSONString(reqDTO);
            RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);

            // 构建请求
            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .build();

            // 发送请求
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("Query config status request failed, status: {}", response.code());
                    throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketQueryConfigStatusFailed,
                            "请求失败，状态码: " + response.code());
                }

                var responseBodyObj = response.body();
                if (responseBodyObj == null) {
                    throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketConfigResultBodyEmpty);
                }
                String responseBody = responseBodyObj.string();
                ReqResult<Map<String, Object>> result = JSON.parseObject(responseBody,
                        new TypeReference<ReqResult<Map<String, Object>>>() {
                        });

                if (result != null && result.isSuccess()) {
                    return result.getData();
                } else {
                    log.error("Query config status failed: {}", result != null ? result.getMessage() : "Unknown error");
                    throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketQueryConfigStatusFailed,
                            result != null ? result.getMessage() : "未知错误");
                }
            }
        } catch (IOException e) {
            log.error("Server query config status API error", e);
            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketQueryConfigStatusFailed, e.getMessage());
        }
    }

    /**
     * 保存并直接发布服务器配置
     * 服务器端接口对应 EcoMarketServerConfigController 中的 saveAndPublish 方法
     *
     * @param reqDTO       保存配置请求DTO
     * @param responseType 响应类型
     * @return 保存并发布结果，失败时返回null
     */
    public <T> T saveAndPublishServerConfig(ServerConfigSaveReqDTO reqDTO, Class<T> responseType) {
        try {
            String url = buildApiUrl(EcoMarketApiConstant.SERVER_API_BASE + "/config/saveAndPublish");

            // 构建请求体
            String jsonBody = JSON.toJSONString(reqDTO);
            RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);

            // 构建请求
            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .build();

            // 发送请求
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("Save-and-publish server config request failed, status: {}", response.code());
                    throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketPublishConfigFailed,
                            "请求失败，状态码: " + response.code());
                }

                var responseBodyObj = response.body();
                if (responseBodyObj == null) {
                    throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketConfigResultBodyEmpty);
                }
                String responseBody = responseBodyObj.string();
                log.debug("Server response body: {}", responseBody);

                // 使用Fastjson2解析响应
                ReqResult<Object> result = JSON.parseObject(responseBody,
                        new TypeReference<ReqResult<Object>>() {
                        });

                if (result != null && result.isSuccess()) {
                    // 将结果转换为指定类型
                    return JSON.parseObject(JSON.toJSONString(result.getData()), responseType);
                } else {
                    log.error("Save-and-publish server config failed: {}", result != null ? result.getMessage() : "Unknown error");
                    throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketPublishConfigFailed,
                            result != null ? result.getMessage() : "未知错误");
                }
            }
        } catch (IOException e) {
            log.error("Server save-and-publish API error", e);
            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketPublishConfigFailed, e.getMessage());
        }
    }

    /**
     * 分页查询服务器配置列表
     * 服务器端接口对应 EcoMarketServerConfigController 中的 list 方法
     *
     * @param reqDTO 查询请求DTO
     * @return 分页查询结果
     */
    public SuperPage<ServerConfigListRespDTO> queryServerConfigList(PageQueryVo<ServerConfigQueryRequest> reqDTO) {
        try {
            log.info("Server config list query: reqDTO={}", reqDTO);
            String url = buildApiUrl(EcoMarketApiConstant.SERVER_API_BASE + "/config/list");

            // 构建请求体
            String jsonBody = JSON.toJSONString(reqDTO);
            RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);

            // 构建请求，添加客户端凭证到请求头
            Request.Builder requestBuilder = new Request.Builder()
                    .url(url)
                    .post(body);

            Request request = requestBuilder.build();

            // 发送请求
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("Query server config list request failed, status: {}", response.code());
                    throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketGetConfigFailed,
                            "状态码: " + response.code());
                }

                var responseBodyObj = response.body();
                if (responseBodyObj == null) {
                    throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketConfigResultBodyEmpty);
                }
                String responseBody = responseBodyObj.string();
                // 使用Fastjson2解析带泛型的分页结果
                ReqResult<IPage<ServerConfigListRespDTO>> result = JSON.parseObject(
                        responseBody,
                        new TypeReference<ReqResult<IPage<ServerConfigListRespDTO>>>() {
                        });

                if (result == null || !result.isSuccess()) {
                    log.error("Query server config list failed: errorCode={}, errorMsg={}",
                            result != null ? result.getCode() : "NULL",
                            result != null ? result.getMessage() : "查询失败");
                    throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketGetConfigFailed,
                            result != null ? result.getMessage() : "查询服务器配置失败");
                }

                var superPage = SuperPage.build(result.getData(), result.getData().getRecords());

                return superPage;
            }
        } catch (IOException e) {
            log.error("Exception calling server config list API", e);
            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketCenterUpgrading);
        }
    }

    /**
     * 查询自动租户启用的配置信息
     * 服务器端接口对应 EcoMarketServerConfigController 中的 autoUse 方法
     *
     * @return 分页查询结果
     */
    public List<ServerConfigDetailRespDTO> queryAutoUseConfigList() {
        try {
            log.info("Query auto tenant enable configs");
            String url = buildApiUrl("/api/system/eco/market/server/autoUse/autoUseList");

            // 构建请求，添加客户端凭证到请求头
            RequestBody body = RequestBody.create("", JSON_MEDIA_TYPE);

            Request.Builder requestBuilder = new Request.Builder()
                    .url(url)
                    .post(body);

            Request request = requestBuilder.build();

            // 发送请求
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("Query server config list request failed, status: {}", response.code());
                    throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketGetConfigFailed,
                            "状态码: " + response.code());
                }

                var responseBodyObj = response.body();
                if (responseBodyObj == null) {
                    throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketConfigResultBodyEmpty);
                }
                String responseBody = responseBodyObj.string();
                // 使用Fastjson2解析带泛型的分页结果
                ReqResult<List<ServerConfigDetailRespDTO>> result = JSON.parseObject(
                        responseBody,
                        new TypeReference<ReqResult<List<ServerConfigDetailRespDTO>>>() {
                        });

                if (result == null || !result.isSuccess()) {
                    log.error("Query server config list failed: errorCode={}, errorMsg={}",
                            result != null ? result.getCode() : "NULL",
                            result != null ? result.getMessage() : "查询失败");
                    throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketGetConfigFailed,
                            result != null ? result.getMessage() : "查询服务器配置失败");
                }

                return result.getData();
            }
        } catch (IOException e) {
            log.error("Exception calling server config list API", e);
            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketCenterUpgrading);
        }
    }

    /**
     * 上传页面zip包到服务器端
     *
     * @param fileBytes    文件字节数组
     * @param fileName     文件名
     * @param contentType  文件类型
     * @param clientId     客户端ID
     * @param clientSecret 客户端密钥
     * @return 上传成功后的文件URL，失败时抛出异常
     */
    public String uploadPageZip(byte[] fileBytes, String fileName, String contentType,
                                String clientId, String clientSecret) {
        try {
            String url = buildApiUrl(EcoMarketApiConstant.ServerConfig.UPLOAD_PAGE_ZIP);
            log.info("Upload page zip to server: fileName={}, size={}, contentType={}", fileName, fileBytes.length, contentType);

            MediaType fileMediaType = MediaType.parse(contentType != null ? contentType : "application/octet-stream");
            RequestBody fileBody = RequestBody.create(fileBytes, fileMediaType);

            // 构建multipart/form-data请求体
            MultipartBody.Builder multipartBuilder = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", fileName, fileBody)
                    .addFormDataPart("fileName", fileName)
                    .addFormDataPart("clientId", clientId)
                    .addFormDataPart("clientSecret", clientSecret);

            RequestBody requestBody = multipartBuilder.build();

            Request request = new Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("Upload file to server request failed, status: {}", response.code());
                    throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketPageExportFailed, "上传文件失败，状态码: " + response.code());
                }

                var responseBodyObj = response.body();
                if (responseBodyObj == null) {
                    throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketConfigResultBodyEmpty);
                }
                String responseBody = responseBodyObj.string();

                ReqResult<String> result = JSON.parseObject(responseBody,
                        new TypeReference<ReqResult<String>>() {
                        });

                if (result != null && result.isSuccess()) {
                    String fileUrl = result.getData();
                    log.info("Page zip upload OK: fileName={}, url={}", fileName, fileUrl);
                    return fileUrl;
                } else {
                    log.error("Page zip upload to server failed: {}", result != null ? result.getMessage() : "Unknown error");
                    throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketPageExportFailed,
                            result != null ? result.getMessage() : "上传页面zip包失败");
                }
            }
        } catch (IOException e) {
            log.error("Server page zip upload API error: fileName={}", fileName, e);
            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketPageExportFailed, "上传页面zip包失败: " + e.getMessage());
        }
    }

    /**
     * 根据importDataKey从生态市场服务器获取导入数据
     *
     * @param importDataKey 导入数据key
     * @param clientId      客户端ID
     * @param clientSecret  客户端密钥
     * @return 导入数据（Map格式）
     */
    public Map<String, Object> getImportData(String importDataKey, String clientId, String clientSecret) {
        try {
            String url = buildApiUrl(EcoMarketApiConstant.ImportData.GET_IMPORT_DATA);

            Map<String, String> reqMap = new java.util.HashMap<>();
            reqMap.put("importDataKey", importDataKey);
            reqMap.put("clientId", clientId);
            reqMap.put("clientSecret", clientSecret);

            String jsonBody = JSON.toJSONString(reqMap);
            RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);

            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("Get import data request failed, status: {}", response.code());
                    throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketGetConfigFailed,
                            "请求失败，状态码: " + response.code());
                }

                var responseBodyObj = response.body();
                if (responseBodyObj == null) {
                    throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketConfigResultBodyEmpty);
                }
                String responseBody = responseBodyObj.string();
                ReqResult<Map<String, Object>> result = JSON.parseObject(responseBody,
                        new TypeReference<ReqResult<Map<String, Object>>>() {
                        });

                if (result != null && result.isSuccess()) {
                    return result.getData();
                } else {
                    log.error("Get import data failed: {}", result != null ? result.getMessage() : "Unknown error");
                    throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketGetConfigFailed,
                            result != null ? result.getMessage() : "获取导入数据失败");
                }
            }
        } catch (IOException e) {
            log.error("Get import data API error", e);
            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketGetConfigFailed, e.getMessage());
        }
    }

}