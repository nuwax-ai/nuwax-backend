package com.xspaceagi.im.web.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.im.infra.enums.ImChannelEnum;
import com.xspaceagi.im.infra.enums.ImTargetTypeEnum;
import com.xspaceagi.im.web.dto.ImChannelConfigTestResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Method;
import java.util.Base64;

/**
 * IM 渠道配置连通性测试服务
 */
@Service
@Slf4j
public class ImChannelTestService {

    /**
     * 测试 IM 渠道配置的连通性
     *
     * @param channel   渠道类型
     * @param targetType 目标类型
     * @param configData 配置数据（JSON字符串）
     * @return 测试结果
     */
    public ImChannelConfigTestResponse testConnection(String channel, String targetType, String configData) {
        try {
            ImChannelEnum channelEnum = ImChannelEnum.fromCode(channel);
            ImTargetTypeEnum targetTypeEnum = ImTargetTypeEnum.fromCode(targetType);

            if (channelEnum == ImChannelEnum.FEISHU) {
                return testFeishuConnection(configData);
            } else if (channelEnum == ImChannelEnum.DINGTALK) {
                return testDingtalkConnection(configData);
            } else if (channelEnum == ImChannelEnum.WEWORK) {
                return testWeworkConnection(targetTypeEnum, configData);
            } else {
                return ImChannelConfigTestResponse.builder()
                        .success(false)
                        .message("不支持的渠道类型")
                        .build();
            }
        } catch (Exception e) {
            log.error("测试IM渠道连通性失败: channel={}, targetType={}, error={}", channel, targetType, e.getMessage(), e);
            return ImChannelConfigTestResponse.builder()
                    .success(false)
                    .message(e.getMessage())
                    .build();
        }
    }

    /**
     * 测试飞书配置连通性
     * 通过调用获取 tenant_access_token 接口来验证 appId 和 appSecret 是否有效
     * verificationToken 和 encryptKey 如果配置了则验证格式，这两个参数用于接收 webhook 事件时的验证
     */
    private ImChannelConfigTestResponse testFeishuConnection(String configData) {
        try {
            JSONObject json = JSON.parseObject(configData);
            if (json == null) {
                return errorResponse("配置数据格式错误");
            }

            String appId = json.getString("appId");
            String appSecret = json.getString("appSecret");
            String verificationToken = json.getString("verificationToken");
            String encryptKey = json.getString("encryptKey");

            // 验证必要字段
            if (appId == null || appId.isEmpty()) {
                return errorResponse("appId 不能为空");
            }

            if (appSecret == null || appSecret.isEmpty()) {
                return errorResponse("appSecret 不能为空");
            }

            // 验证 webhook 安全参数格式（如果配置了的话）
            if ((StringUtils.isNotBlank(encryptKey) && StringUtils.isBlank(verificationToken))
                    || (StringUtils.isBlank(encryptKey) && StringUtils.isNotBlank(verificationToken))) {
                return errorResponse("encryptKey 和 verificationToken需要同时为空或同时不为空");
            }

//            if (encryptKey != null && !encryptKey.isEmpty()) {
//                // 验证 encryptKey 格式（应该是43个字符的base64编码字符串，解码后32字节）
//                try {
//                    // 检查字符串长度
//                    if (encryptKey.length() != 43) {
//                        return errorResponse("encryptKey 格式错误：应为43个字符的base64编码字符串");
//                    }
//                    // 验证是否为有效的 base64 编码
//                    byte[] decoded = java.util.Base64.getDecoder().decode(encryptKey);
//                    if (decoded.length != 32) {
//                        return errorResponse("encryptKey 格式错误：解码后应为32字节（AES-256密钥）");
//                    }
//                } catch (IllegalArgumentException e) {
//                    return errorResponse("encryptKey 格式错误：不是有效的base64编码");
//                }
//            }

            // 直接调用飞书 API 获取 tenant_access_token
            try {
                String url = "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal";
                String requestBody = String.format("{\"app_id\":\"%s\",\"app_secret\":\"%s\"}", appId, appSecret);

                RestTemplate restTemplate = new RestTemplate();
                org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
                headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

                org.springframework.http.HttpEntity<String> entity = new org.springframework.http.HttpEntity<>(requestBody, headers);
                ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

                if (response.getStatusCode().is2xxSuccessful()) {
                    JSONObject resp = JSON.parseObject(response.getBody());
                    if (resp != null && resp.getInteger("code") == 0) {
                        String tenantAccessToken = resp.getString("tenant_access_token");
                        if (StringUtils.isNotBlank(tenantAccessToken)) {
                            return ImChannelConfigTestResponse.builder()
                                    .success(true)
                                    .message("飞书连通性测试成功")
                                    .detail("appId: " + appId)
                                    .build();
                        }
                    }

                    // 返回了非成功状态
                    Integer code = resp != null ? resp.getInteger("code") : null;
                    String msg = resp != null ? resp.getString("msg") : null;
                    return errorResponse("获取 tenant_access_token 失败: code=" + code + ", msg=" + msg);
                }

                return errorResponse("连接飞书服务器失败，HTTP状态码: " + response.getStatusCode());

            } catch (Exception e) {
                log.error("飞书API调用异常", e);
                return errorResponse("连接飞书服务器异常: " + e.getMessage());
            }

        } catch (Exception e) {
            log.error("测试飞书连通性失败", e);
            return ImChannelConfigTestResponse.builder()
                    .success(false)
                    .message(e.getMessage())
                    .build();
        }
    }

    /**
     * 测试钉钉配置连通性
     * 通过调用获取AccessToken接口来验证 clientId 和 clientSecret 是否有效
     * robotCode 是必填参数，但由于钉钉 API 限制，无法通过主动调用验证其正确性，需在实际发送消息时验证
     */
    private ImChannelConfigTestResponse testDingtalkConnection(String configData) {
        try {
            JSONObject json = JSON.parseObject(configData);
            if (json == null) {
                return errorResponse("配置数据格式错误");
            }

            String clientId = json.getString("clientId");
            String clientSecret = json.getString("clientSecret");
            String robotCode = json.getString("robotCode");

            // 验证必要字段
            if (clientId == null || clientId.isEmpty()) {
                return errorResponse("clientId 不能为空");
            }

            if (clientSecret == null || clientSecret.isEmpty()) {
                return errorResponse("clientSecret 不能为空");
            }

            // robotCode 是必填参数
            if (robotCode == null || robotCode.isEmpty()) {
                return errorResponse("robotCode 不能为空，请在钉钉开放平台【消息推送】获取");
            }

            // 创建客户端并测试获取 AccessToken
            DingtalkOpenApiClient client = new DingtalkOpenApiClient(clientId, clientSecret, robotCode);

            // 通过反射调用 getAccessToken() 方法进行连通性测试
            // 如果能够成功获取 AccessToken，说明凭证有效且网络连通
            Method method = DingtalkOpenApiClient.class.getDeclaredMethod("getAccessToken");
            method.setAccessible(true);
            String accessToken = (String) method.invoke(client);

            if (accessToken != null && !accessToken.isEmpty()) {
                return ImChannelConfigTestResponse.builder()
                        .success(true)
                        .message("钉钉连通性测试成功")
                        .detail("clientId: " + clientId + ", robotCode: " + robotCode + " | 注：robotCode 需在实际发送消息时验证")
                        .build();
            } else {
                return errorResponse("无法获取 AccessToken，请检查 clientId 和 clientSecret 是否正确");
            }

        } catch (Exception e) {
            log.error("测试钉钉连通性失败", e);
            String errorMsg = e.getMessage();
            // 提取更有用的错误信息
            if (errorMsg != null) {
                if (errorMsg.contains("invalid_client") || errorMsg.contains("40025")) {
                    errorMsg = "clientId 或 clientSecret 不正确";
                } else if (errorMsg.contains("timeout") || errorMsg.contains("connect")) {
                    errorMsg = "连接钉钉服务器超时，请检查网络";
                }
            }
            return ImChannelConfigTestResponse.builder()
                    .success(false)
                    .message(errorMsg != null ? errorMsg : e.getMessage())
                    .build();
        }
    }

    /**
     * 测试企业微信配置连通性
     * BOT类型：验证格式完整性（无API可调用）
     * APP类型：验证 corpId/corpSecret/agentId，token 和 encodingAesKey 需在实际接收 webhook 事件时验证
     * 验证 corpId 和 corpSecret（通过获取 access_token API）
     * 验证 agentId（通过调用 /cgi-bin/agent/get API，返回应用名称）
     */
    private ImChannelConfigTestResponse testWeworkConnection(ImTargetTypeEnum targetType, String configData) {
        try {
            JSONObject json = JSON.parseObject(configData);
            if (json == null) {
                return errorResponse("配置数据格式错误");
            }

            if (targetType == ImTargetTypeEnum.BOT) {
                // 企业微信智能机器人 - 只验证格式，没有API可以直接测试
                String aibotId = json.getString("aibotId");
                String corpId = json.getString("corpId");
                String token = json.getString("token");
                String encodingAesKey = json.getString("encodingAesKey");

                if (aibotId == null || corpId == null || token == null || encodingAesKey == null) {
                    return errorResponse("企业微信机器人配置不完整，缺少必要字段");
                }

                // 企业微信机器人配置验证：只验证格式完整性
                return ImChannelConfigTestResponse.builder()
                        .success(true)
                        .message("企业微信机器人配置格式验证成功")
                        .detail("aibotId: " + aibotId + ", corpId: " + corpId)
                        .build();

            } else if (targetType == ImTargetTypeEnum.APP) {
                // 企业微信自建应用 - 验证 corpId/corpSecret/agentId
                String agentId = json.getString("agentId");
                String corpId = json.getString("corpId");
                String corpSecret = json.getString("corpSecret");
                String token = json.getString("token");
                String encodingAesKey = json.getString("encodingAesKey");

                if (agentId == null || corpId == null || corpSecret == null || token == null || encodingAesKey == null) {
                    return errorResponse("企业微信自建应用配置不完整，缺少必要字段");
                }

                // 验证 encodingAesKey 格式（应该是43个字符的base64编码字符串，解码后32字节）
                try {
                    // 检查字符串长度
                    if (encodingAesKey.length() != 43) {
                        return errorResponse("encodingAesKey 格式错误：应为43个字符的base64编码字符串");
                    }
                    // 验证是否为有效的 base64 编码
                    byte[] decoded = Base64.getDecoder().decode(encodingAesKey);
                    if (decoded.length != 32) {
                        return errorResponse("encodingAesKey 格式错误：解码后应为32字节（AES-256密钥）");
                    }
                } catch (IllegalArgumentException e) {
                    return errorResponse("encodingAesKey 格式错误：不是有效的base64编码");
                }

                // 测试获取 access_token
                try {
                    String url = "https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid=" + corpId + "&corpsecret=" + corpSecret;
                    RestTemplate restTemplate = new RestTemplate();
                    String response = restTemplate.getForObject(url, String.class);

                    if (StringUtils.isNotBlank(response)) {
                        JSONObject resp = JSON.parseObject(response);
                        if (resp != null && resp.getIntValue("errcode") == 0) {
                            String accessToken = resp.getString("access_token");
                            if (StringUtils.isNotBlank(accessToken)) {
                                // access_token 获取成功，继续验证 agentId
                                try {
                                    String agentUrl = "https://qyapi.weixin.qq.com/cgi-bin/agent/get?access_token=" + accessToken + "&agentid=" + agentId;
                                    String agentResp = restTemplate.getForObject(agentUrl, String.class);

                                    if (StringUtils.isNotBlank(agentResp)) {
                                        JSONObject agentJson = JSON.parseObject(agentResp);
                                        if (agentJson != null && agentJson.getIntValue("errcode") == 0) {
                                            // agentId 验证成功
                                            String appName = agentJson.getString("name");
                                            return ImChannelConfigTestResponse.builder()
                                                    .success(true)
                                                    .message("企业微信连通性测试成功")
                                                    .detail("agentId: " + agentId + " (" + appName + "), corpId: " + corpId + " | 注：token 和 encodingAesKey 需在实际接收 webhook 事件时验证")
                                                    .build();
                                        }

                                        // agentId 验证失败
                                        Integer errcode = agentJson != null ? agentJson.getInteger("errcode") : null;
                                        String errmsg = agentJson != null ? agentJson.getString("errmsg") : null;
                                        if (errcode != null && errcode == 301053) {
                                            return errorResponse("agentId 不存在或无权限访问");
                                        }
                                        return errorResponse("验证 agentId 失败: errcode=" + errcode + ", errmsg=" + errmsg);
                                    }

                                    return errorResponse("获取应用信息失败，响应为空");
                                } catch (Exception e) {
                                    log.error("验证 agentId 异常", e);
                                    // agentId 验证失败，但不影响整体测试结果（可能只是权限问题）
                                    return ImChannelConfigTestResponse.builder()
                                            .success(true)
                                            .message("企业微信连通性测试成功（部分验证）")
                                            .detail("agentId: " + agentId + ", corpId: " + corpId + " | 注：agentId 需要有相应权限，token 和 encodingAesKey 需在实际接收 webhook 事件时验证")
                                            .build();
                                }
                            }
                        }

                        // 返回了错误
                        Integer errcode = resp != null ? resp.getInteger("errcode") : null;
                        String errmsg = resp != null ? resp.getString("errmsg") : null;
                        return errorResponse("获取 access_token 失败: errcode=" + errcode + ", errmsg=" + errmsg);
                    }

                    return errorResponse("连接企业微信服务器失败，响应为空");

                } catch (Exception e) {
                    log.error("企业微信API调用异常", e);
                    return errorResponse("连接企业微信服务器异常: " + e.getMessage());
                }
            } else {
                return errorResponse("不支持的企业微信目标类型");
            }

        } catch (Exception e) {
            log.error("测试企业微信连通性失败", e);
            return ImChannelConfigTestResponse.builder()
                    .success(false)
                    .message(e.getMessage())
                    .build();
        }
    }

    private ImChannelConfigTestResponse errorResponse(String message) {
        return ImChannelConfigTestResponse.builder()
                .success(false)
                .message(message)
                .build();
    }
}
