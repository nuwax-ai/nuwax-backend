package com.xspaceagi.modelproxy.infra.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.agent.core.spec.utils.TikTokensUtil;
import com.xspaceagi.log.sdk.vo.LogDocument;
import com.xspaceagi.modelproxy.infra.rpc.LogSaveRpcService;
import com.xspaceagi.modelproxy.spec.utils.AnthropicSSEParser;
import com.xspaceagi.modelproxy.spec.utils.OpenAISSEParser;
import com.xspaceagi.system.sdk.common.TraceContext;
import com.xspaceagi.system.sdk.server.IUserMetricRpcService;
import com.xspaceagi.system.sdk.service.ScheduleTaskApiService;
import com.xspaceagi.system.sdk.service.TaskExecuteService;
import com.xspaceagi.system.sdk.service.dto.BizType;
import com.xspaceagi.system.sdk.service.dto.ScheduleTaskDto;
import com.xspaceagi.system.spec.utils.RedisUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service("tokenLogService")
public class TokenLogService implements TaskExecuteService {

    @Resource
    private RedisUtil redisUtil;

    @Resource
    private ScheduleTaskApiService scheduleTaskApiService;

    @Resource
    private IUserMetricRpcService userMetricRpcService;

    @Resource
    private LogSaveRpcService logSaveRpcService;

    @Value("${model-api-proxy.save-log:false}")
    private String saveLog;

    @PostConstruct
    public void init() {
        scheduleTaskApiService.start(ScheduleTaskDto.builder()
                .taskId("tokenLogService")
                .beanId("tokenLogService")
                .maxExecTimes(Long.MAX_VALUE)
                .cron(ScheduleTaskDto.Cron.EVERY_2_SECOND.getCron())
                .params(Map.of())
                .build());
    }

    public void log(String text) {
        redisUtil.leftPush("token_log", text);
    }

    @Override
    public Mono<Boolean> asyncExecute(ScheduleTaskDto scheduleTask) {
        Object val = redisUtil.rightPop("token_log");
        while (val != null) {
            JSONObject jsonObject = JSONObject.parseObject(val.toString());
            String apiProtocol = jsonObject.getString("apiProtocol");
            TraceContext traceContext = jsonObject.getObject("traceContext", TraceContext.class);
            //记录日志以及token计算
            if (StringUtils.isNotBlank(apiProtocol)) {
                Long userId = jsonObject.getLong("userId");
                Long tenantId = jsonObject.getLong("tenantId");
                Long modelId = jsonObject.getLong("modelId");
                log.debug("apiProtocol:{},userId:{},tenantId:{},modelId:{}", apiProtocol, userId, tenantId, modelId);
                String userName = jsonObject.getString("userName");
                String requestId = jsonObject.getString("requestId");
                String conversationId = jsonObject.getString("conversationId");
                String requestTime = jsonObject.getString("requestTime");
                String requestBody = jsonObject.getString("requestBody");
                String model = jsonObject.getString("model");
                String responseTime = jsonObject.getString("responseTime");
                String responseBody = jsonObject.getString("responseBody");
                //当前只支持anthropic和openai的token记录
                // responseBody 为对应的sse完整数据
                long cacheInputTokens = 0L;
                long inputTokens = 0L;
                long outputTokens = 0L;
                long cacheCreationInputTokens = 0L;
                String content = null;
                if ("Anthropic".equalsIgnoreCase(apiProtocol)) {
                    AnthropicSSEParser.TokenUsage tokenUsage = AnthropicSSEParser.extractTokenUsage(responseBody);
                    cacheCreationInputTokens = tokenUsage.cacheCreationInputTokens;
                    inputTokens = (long) (tokenUsage.inputTokens + (tokenUsage.cacheCreationInputTokens * 1.25));//一般厂商缓存创建输入的价格比正常输入的要贵25%
                    outputTokens = tokenUsage.outputTokens;
                    cacheInputTokens = tokenUsage.cacheInputTokens;
                    content = tokenUsage.text;
                    if (tokenUsage.stopAccount) {
                        String backendApiKeyMd5 = jsonObject.getString("backendApiKeyMd5");
                        if (StringUtils.isNotBlank(backendApiKeyMd5)) {
                            redisUtil.set("stop_account_" + backendApiKeyMd5, "true", tokenUsage.stopTimeSeconds);
                            log.warn("backendApiKeyMd5:{} stop account, stopTime:{}, backendApiKey {}, message {}",
                                    backendApiKeyMd5, tokenUsage.stopTimeSeconds, jsonObject.getString("backendApiKey"), tokenUsage.message);
                        }
                    }
                } else if ("OpenAi".equalsIgnoreCase(apiProtocol)) {
                    OpenAISSEParser.TokenUsage tokenUsage = OpenAISSEParser.extractTokenUsage(responseBody);
                    cacheInputTokens = tokenUsage.cachedTokens;
                    cacheCreationInputTokens = tokenUsage.cacheCreationInputTokens;
                    inputTokens = (long) (tokenUsage.promptTokens - cacheInputTokens + tokenUsage.cacheCreationInputTokens * 1.25);
                    outputTokens = tokenUsage.completionTokens;
                    content = tokenUsage.content;
                }
                if (inputTokens == 0) {
                    //大致计算输入token
                    inputTokens = TikTokensUtil.tikTokensCount(requestBody);
                }
                if (outputTokens == 0) {
                    outputTokens = TikTokensUtil.tikTokensCount(content);
                }
                if (inputTokens > 0) {
                    userMetricRpcService.incrementMetricAllPeriods(tenantId, userId, BizType.TOKEN_USAGE.getCode(), BigDecimal.valueOf(inputTokens + outputTokens));
                }
                if ("true".equalsIgnoreCase(saveLog)) {
                    long spaceId = -1L;
                    if (traceContext != null && CollectionUtils.isNotEmpty(traceContext.getTraceTargets())) {
                        TraceContext.TraceTarget traceTarget = traceContext.getTraceTargets().get(traceContext.getTraceTargets().size() - 1);
                        spaceId = traceTarget.getSpaceId() != null ? traceTarget.getSpaceId() : -1L;
                    }
                    logSaveRpcService.saveLog(LogDocument.builder()
                            .from("model-proxy")
                            .id(UUID.randomUUID().toString().replace("-", ""))
                            .tenantId(tenantId)
                            .spaceId(spaceId)
                            .requestStartTime(Long.parseLong(requestTime))
                            .requestEndTime(Long.parseLong(responseTime))
                            .requestId(requestId)
                            .input(requestBody)
                            .createTime(Long.parseLong(responseTime))
                            .output(responseBody)
                            .userId(userId)
                            .userName(userName)
                            .targetType("Model")
                            .targetId(String.valueOf(modelId))
                            .conversationId(conversationId)
                            .targetName(model)
                            .cacheInputToken((int) cacheInputTokens)
                            .cacheCreationInputToken((int) cacheCreationInputTokens)
                            .inputToken((int) inputTokens)
                            .outputToken((int) outputTokens)
                            .apiKey(traceContext != null && traceContext.getApiKey() != null ? TraceContext.maskApiKey(traceContext.getApiKey()) : "")
                            .resultCode(traceContext != null && traceContext.isError() ? traceContext.getErrorCode() : "0000")
                            .resultMsg(traceContext != null && traceContext.isError() ? traceContext.getErrorMessage() : "success")
                            .apiKey(traceContext != null && traceContext.getApiKey() != null ? traceContext.getApiKey() : "")
                            .build());
                }
                if (traceContext != null) {
                    traceContext.setTokenUsage(new TraceContext.TokenUsage(cacheInputTokens, inputTokens, outputTokens));
                    redisUtil.rightPush("bill:queue", JSON.toJSONString(traceContext));
                } else {
                    log.warn("traceContext is null, jsonObject:{}", jsonObject);
                }
            }
            val = redisUtil.rightPop("token_log");
        }
        return Mono.just(false);
    }
}
