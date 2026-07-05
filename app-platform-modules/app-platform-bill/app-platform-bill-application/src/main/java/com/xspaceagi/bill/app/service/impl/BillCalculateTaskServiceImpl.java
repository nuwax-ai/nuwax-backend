package com.xspaceagi.bill.app.service.impl;

import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.agent.core.sdk.IAgentRpcService;
import com.xspaceagi.agent.core.sdk.IModelRpcService;
import com.xspaceagi.agent.core.sdk.dto.*;
import com.xspaceagi.bill.app.service.BillRevenueAppService;
import com.xspaceagi.bill.infra.dao.entity.BillResourceStat;
import com.xspaceagi.bill.infra.dao.service.IBillResourceStatService;
import com.xspaceagi.bill.sdk.dto.AddRevenueRequest;
import com.xspaceagi.bill.spec.enums.ResourceStatTypeEnum;
import com.xspaceagi.bill.spec.enums.RevenueTargetTypeEnum;
import com.xspaceagi.bill.spec.enums.RevenueTypeEnum;
import com.xspaceagi.credit.sdk.dto.CreditDeductRequest;
import com.xspaceagi.credit.sdk.rpc.ICreditRpcService;
import com.xspaceagi.credit.spec.enums.CreditTypeEnum;
import com.xspaceagi.pricing.sdk.dto.ModelPriceTierDTO;
import com.xspaceagi.pricing.sdk.dto.PricingConfigDTO;
import com.xspaceagi.pricing.sdk.dto.QueryPricingInfoRequest;
import com.xspaceagi.pricing.sdk.dto.UpdateTrialCountRequest;
import com.xspaceagi.pricing.sdk.rpc.IPricingRpcService;
import com.xspaceagi.pricing.spec.enums.PricingTypeEnum;
import com.xspaceagi.pricing.spec.enums.TargetTypeEnum;
import com.xspaceagi.subscription.sdk.rpc.ISubscriptionRpcService;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import com.xspaceagi.system.sdk.common.TraceContext;
import com.xspaceagi.system.sdk.service.AbstractTaskExecuteService;
import com.xspaceagi.system.sdk.service.ScheduleTaskApiService;
import com.xspaceagi.system.sdk.service.dto.ScheduleTaskDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.utils.RedisUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service("billCalculateTaskService")
public class BillCalculateTaskServiceImpl extends AbstractTaskExecuteService {

    @Resource
    private ScheduleTaskApiService scheduleTaskApiService;

    @Resource
    private ICreditRpcService iCreditRpcService;

    @Resource
    private IPricingRpcService iPricingRpcService;

    @Resource
    private BillRevenueAppService billRevenueAppService;

    @Resource
    private IBillResourceStatService iBillResourceStatService;

    @Resource
    private TenantConfigApplicationService tenantConfigApplicationService;

    @Resource
    private ISubscriptionRpcService iSubscriptionRpcService;

    @Resource
    private IAgentRpcService iAgentRpcService;

    @Resource
    private IModelRpcService iModelRpcService;

    @Resource
    private RedisUtil redisUtil;

    @PostConstruct
    public void init() {
        scheduleTaskApiService.start(ScheduleTaskDto.builder()
                .taskId("billCalculateTaskService")
                .beanId("billCalculateTaskService")
                .maxExecTimes(Long.MAX_VALUE)
                .cron(ScheduleTaskDto.Cron.EVERY_10_SECOND.getCron())
                .params(Map.of())
                .build());
    }


    @Override
    protected boolean execute(ScheduleTaskDto scheduleTaskDto) {
        try {
            execute0();
        } catch (Exception e) {
            log.error("execute error", e);
        }
        return false;
    }

    private void execute0() {
        Map<Long, TenantConfigDto> tenantConfigMap = new HashMap<>();
        Object val = redisUtil.rightPop("bill:queue");
        while (val != null) {
            TraceContext traceContext = JSONObject.parseObject(val.toString(), TraceContext.class);
            if (traceContext != null && CollectionUtils.isNotEmpty(traceContext.getTraceTargets())) {
                try {
                    TenantConfigDto tenantConfig = tenantConfigMap.get(traceContext.getTenantId());
                    if (tenantConfig == null) {
                        tenantConfig = tenantConfigApplicationService.getTenantConfig(traceContext.getTenantId());
                        tenantConfigMap.put(traceContext.getTenantId(), tenantConfig);
                    }
                    RequestContext<Object> requestContext = new RequestContext<>();
                    requestContext.setTenantId(traceContext.getTenantId());
                    requestContext.setTenantConfig(tenantConfig);
                    RequestContext.set(requestContext);
                    handleCalculateBill(traceContext);
                } catch (Exception e) {
                    log.error("Calculate bill error {}", val, e);
                } finally {
                    RequestContext.remove();
                }
            }
            log.info("Calculate bill: {}", val);
            val = redisUtil.rightPop("bill:queue");
        }
    }

    private void handleCalculateBill(TraceContext traceContext) {
        TenantConfigDto tenantConfig = (TenantConfigDto) RequestContext.get().getTenantConfig();
        TraceContext.TraceTarget traceTarget = traceContext.getTraceTargets().get(traceContext.getTraceTargets().size() - 1);
        QueryPricingInfoRequest request = new QueryPricingInfoRequest();
        request.setTargetId(traceTarget.getTargetId());
        if (traceTarget.getTargetType() == TraceContext.TraceTargetType.Agent) {
            request.setTargetType(TargetTypeEnum.AGENT);
        } else if (traceTarget.getTargetType() == TraceContext.TraceTargetType.Model) {
            request.setTargetType(TargetTypeEnum.MODEL);
        } else if (traceTarget.getTargetType() == TraceContext.TraceTargetType.Plugin) {
            request.setTargetType(TargetTypeEnum.PLUGIN);
        } else if (traceTarget.getTargetType() == TraceContext.TraceTargetType.Workflow) {
            request.setTargetType(TargetTypeEnum.WORKFLOW);
        } else if (traceTarget.getTargetType() == TraceContext.TraceTargetType.Mcp) {
            request.setTargetType(TargetTypeEnum.MCP);
        } else {
            return;
        }
        request.setTenantId(traceContext.getTenantId());
        BigDecimal creditAmount = null;
        BigDecimal feeAmount = null;
        TraceContext.TokenUsage tokenUsage = traceContext.getTokenUsage();
        Long salerId = -1L;
        if (traceTarget.getTargetType() == TraceContext.TraceTargetType.Workflow) {
            ReqResult<WorkflowInfoDto> publishedWorkflowInfo = iAgentRpcService.getPublishedWorkflowInfo(Long.parseLong(traceTarget.getTargetId()), null);
            if (publishedWorkflowInfo == null) {
                log.warn("getPublishedWorkflowInfo error {}", traceTarget.getTargetId());
                return;
            }
            salerId = publishedWorkflowInfo.getData().getCreatorId();
        }
        if (traceTarget.getTargetType() == TraceContext.TraceTargetType.Plugin) {
            ReqResult<PluginInfoDto> publishedPluginInfo = iAgentRpcService.getPublishedPluginInfo(Long.parseLong(traceTarget.getTargetId()), null);
            if (publishedPluginInfo == null) {
                log.warn("getPublishedPluginInfo error {}", traceTarget.getTargetId());
                return;
            }
            salerId = publishedPluginInfo.getData().getCreatorId();
        }

        if (traceTarget.getTargetType() == TraceContext.TraceTargetType.Agent) {
            ReqResult<AgentInfoDto> publishedAgentInfo = iAgentRpcService.queryPublishedAgentInfo(Long.parseLong(traceTarget.getTargetId()));
            if (publishedAgentInfo == null || publishedAgentInfo.getData() == null) {
                log.warn("queryPublishedAgentInfo error {}", traceTarget.getTargetId());
                return;
            }
            salerId = publishedAgentInfo.getData().getCreatorId();
        }

        if (traceTarget.getTargetType() == TraceContext.TraceTargetType.Model) {
            ModelInfoDto modelInfo = iModelRpcService.getModelInfo(Long.parseLong(traceTarget.getTargetId()));
            if (modelInfo == null) {
                log.warn("getModelInfo error {}", traceTarget.getTargetId());
                return;
            }
            salerId = modelInfo.isTenantModel() ? -1L : modelInfo.getCreatorId();
        }

        log.debug("开始计费 tenantId={} userId={} targetType={} targetId={} traceId={}", traceContext.getTenantId(), traceContext.getBillUserId(),
                traceTarget.getTargetType(), traceTarget.getTargetId(), traceContext.getTraceId());
        if (traceContext.isError()) {
            log.warn("error call, traceContext {}", traceContext);
        }

        if (tenantConfig.getEnableSubscription() != null && tenantConfig.getEnableSubscription() == 1 && !traceContext.isError() && !traceContext.isDevTest()) {
            PricingConfigDTO pricingConfigDTO = iPricingRpcService.queryPricingInfo(request);
            if (pricingConfigDTO != null && pricingConfigDTO.getStatus() == 1) {
                log.info("定价配置匹配成功 pricingType={} price={} trialCount={}",
                        pricingConfigDTO.getPricingType(), pricingConfigDTO.getPrice(), pricingConfigDTO.getTrialCount());
                boolean isToolCall = false;
                if (pricingConfigDTO.getPricingType() == PricingTypeEnum.ONE_TIME && (pricingConfigDTO.getTargetType() == TargetTypeEnum.WORKFLOW || pricingConfigDTO.getTargetType() == TargetTypeEnum.PLUGIN)) {
                    //积分扣减计算
                    creditAmount = pricingConfigDTO.getPrice().multiply(BigDecimal.valueOf(tenantConfig.getCreditExchangeRate()));
                    feeAmount = pricingConfigDTO.getPrice();
                    isToolCall = true;
                }
                if (pricingConfigDTO.getPricingType() == PricingTypeEnum.SECOND && traceContext.getDurationUsage() != null && traceContext.getDurationUsage().duration > 0
                        && (pricingConfigDTO.getTargetType() == TargetTypeEnum.WORKFLOW || pricingConfigDTO.getTargetType() == TargetTypeEnum.PLUGIN)) {
                    //积分扣减计算
                    feeAmount = pricingConfigDTO.getPrice().multiply(BigDecimal.valueOf(traceContext.getDurationUsage().duration));
                    creditAmount = feeAmount.multiply(BigDecimal.valueOf(tenantConfig.getCreditExchangeRate()));
                    isToolCall = true;
                }
                if (pricingConfigDTO.getPricingType() == PricingTypeEnum.MILLION_TOKEN && tokenUsage != null && tokenUsage.outputTokens > 0
                        && (pricingConfigDTO.getTargetType() == TargetTypeEnum.WORKFLOW || pricingConfigDTO.getTargetType() == TargetTypeEnum.PLUGIN)) {
                    //积分扣减计算
                    feeAmount = pricingConfigDTO.getPrice().multiply(BigDecimal.valueOf(tokenUsage.outputTokens)).divide(BigDecimal.valueOf(1000000), RoundingMode.HALF_UP);
                    creditAmount = feeAmount.multiply(BigDecimal.valueOf(tenantConfig.getCreditExchangeRate()));
                    isToolCall = true;
                }
                if (pricingConfigDTO.getPricingType() == PricingTypeEnum.TIERED && pricingConfigDTO.getTargetType() == TargetTypeEnum.MODEL) {
                    //阶梯计费
                    if (tokenUsage != null && CollectionUtils.isNotEmpty(pricingConfigDTO.getModelPriceTiers())) {
                        long contextLength = tokenUsage.cacheInputTokens + tokenUsage.inputTokens + tokenUsage.outputTokens;
                        List<ModelPriceTierDTO> sortedTiers = pricingConfigDTO.getModelPriceTiers().stream()
                                .sorted(Comparator.comparingInt(ModelPriceTierDTO::getContextLength))
                                .toList();
                        ModelPriceTierDTO matchedTier = null;
                        for (ModelPriceTierDTO tier : sortedTiers) {
                            matchedTier = tier;
                            if (contextLength <= tier.getContextLength() * 1000L) {
                                break;
                            }
                        }
                        if (matchedTier != null) {
                            BigDecimal inputPrice = matchedTier.getInputPrice() != null ? matchedTier.getInputPrice() : BigDecimal.ZERO;
                            BigDecimal outputPrice = matchedTier.getOutputPrice() != null ? matchedTier.getOutputPrice() : BigDecimal.ZERO;
                            BigDecimal cachePrice = matchedTier.getCachePrice() != null ? matchedTier.getCachePrice() : BigDecimal.ZERO;
                            feeAmount = BigDecimal.valueOf(tokenUsage.inputTokens).multiply(inputPrice)
                                    .add(BigDecimal.valueOf(tokenUsage.outputTokens).multiply(outputPrice))
                                    .add(BigDecimal.valueOf(tokenUsage.cacheInputTokens).multiply(cachePrice));
                            feeAmount = feeAmount.divide(BigDecimal.valueOf(1000000), RoundingMode.HALF_UP);
                            creditAmount = feeAmount.multiply(BigDecimal.valueOf(tenantConfig.getCreditExchangeRate()));
                            log.info("阶梯计费匹配档位 contextLength={}k matchedTier={} inputTokens={} outputTokens={} cacheTokens={} feeAmount={} creditAmount={}",
                                    matchedTier.getContextLength(), matchedTier.getId(),
                                    tokenUsage.inputTokens, tokenUsage.outputTokens, tokenUsage.cacheInputTokens,
                                    feeAmount, creditAmount);
                        } else {
                            log.warn("阶梯计费未匹配到合适档位 modelPriceTiers={} contextLength={}",
                                    pricingConfigDTO.getModelPriceTiers().size(), contextLength);
                        }

                    }
                }

                Long billUserId = traceContext.getBillUserId();
                if (traceTarget.getBillUserId() != null) {
                    billUserId = traceTarget.getBillUserId();
                }
                //积分扣减
                CreditDeductRequest creditDeductRequest = new CreditDeductRequest();
                creditDeductRequest.setTenantId(traceContext.getTenantId());
                creditDeductRequest.setUserId(billUserId);
                creditDeductRequest.setCreditType(isToolCall ? CreditTypeEnum.TOOL_CALL : CreditTypeEnum.MODEL_CALL);
                creditDeductRequest.setAmount(creditAmount);
                creditDeductRequest.setAllowNegative(true);
                creditDeductRequest.setBizNo(UUID.randomUUID().toString().replace("-", ""));
                creditDeductRequest.setRemark(targetsToSting(traceContext.getTraceTargets()));
                log.info("积分扣减 userId={} creditType={} amount={}", billUserId, isToolCall ? "TOOL_CALL" : "MODEL_CALL", creditAmount);
                iCreditRpcService.deductCredit(creditDeductRequest);

                //收益发放
                AddRevenueRequest addRevenueRequest = new AddRevenueRequest();
                addRevenueRequest.setTenantId(traceContext.getTenantId());
                addRevenueRequest.setUserId(salerId);
                addRevenueRequest.setType(isToolCall ? RevenueTypeEnum.TOOL_CALL : RevenueTypeEnum.MODEL_CALL);
                addRevenueRequest.setAmount(feeAmount);
                addRevenueRequest.setTargetType(RevenueTargetTypeEnum.fromCode(traceTarget.getTargetType().name()));
                addRevenueRequest.setTargetId(Long.parseLong(traceTarget.getTargetId()));
                addRevenueRequest.setBizNo(UUID.randomUUID().toString().replace("-", ""));
                addRevenueRequest.setRemark(traceTarget.getName() + "[" + traceTarget.getTargetId() + "]");
                addRevenueRequest.setExtra(Map.of("traceId", traceContext.getTraceId() == null ? "" : traceContext.getTraceId()));
                billRevenueAppService.addRevenue(addRevenueRequest);
                log.info("收益发放 salerId={} type={} feeAmount={} bizNo={}",
                        salerId, isToolCall ? "TOOL_CALL" : "MODEL_CALL", feeAmount, traceContext.getTraceId());
            } else if (pricingConfigDTO != null && pricingConfigDTO.getStatus() == 1 && (pricingConfigDTO.getPricingType() == PricingTypeEnum.MONTHLY || pricingConfigDTO.getPricingType() == PricingTypeEnum.BUYOUT)) {
                // 在订阅中记录调用次数
                if (traceContext.getSubscriptionId() != null) {
                    iSubscriptionRpcService.incrementCallCount(traceContext.getTenantId(), traceContext.getSubscriptionId(), 1);
                }
                //没有任何订阅的时候更新试用次数
                if (pricingConfigDTO.getTrialCount() != null && pricingConfigDTO.getTrialCount() > 0 && traceContext.getSubscriptionId() == null) {
                    UpdateTrialCountRequest trialReq = new UpdateTrialCountRequest();
                    trialReq.setTenantId(traceContext.getTenantId());
                    trialReq.setUserId(traceContext.getUserId());
                    trialReq.setTargetType(TargetTypeEnum.AGENT);
                    trialReq.setTargetId(traceTarget.getTargetId());
                    iPricingRpcService.updateTrialCount(trialReq);
                }
            } else {
                log.warn("定价配置未命中或已禁用 tenantId={} targetType={} targetId={} pricingStatus={}",
                        traceContext.getTenantId(), traceTarget.getTargetType(), traceTarget.getTargetId(),
                        pricingConfigDTO != null ? pricingConfigDTO.getStatus() : null);
            }
        }
        String dt = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        BillResourceStat userStat = BillResourceStat.builder()
                .tenantId(tenantConfig.getTenantId())
                .userId(traceContext.getBillUserId())
                .type(ResourceStatTypeEnum.CONSUMPTION.getCode())
                .targetType(traceTarget.getTargetType().name())
                .targetId(Long.parseLong(traceTarget.getTargetId()))
                .dt(dt)
                .callCount(1L)
                .callFailedCount(traceContext.isError() ? 1L : 0L)
                .creditAmount(creditAmount)
                .feeAmount(feeAmount)
                .cacheInputTokens(tokenUsage != null && !traceContext.isError() ? tokenUsage.cacheInputTokens : 0L)
                .inputTokens(tokenUsage != null && !traceContext.isError() ? tokenUsage.inputTokens : 0L)
                .outputTokens(tokenUsage != null && !traceContext.isError() ? tokenUsage.outputTokens : 0L)
                .build();
        iBillResourceStatService.appendStat(userStat);

        BillResourceStat salerStat = BillResourceStat.builder()
                .tenantId(tenantConfig.getTenantId())
                .userId(salerId)
                .type(ResourceStatTypeEnum.SALES.getCode())
                .targetType(traceTarget.getTargetType().name())
                .targetId(Long.parseLong(traceTarget.getTargetId()))
                .dt(dt)
                .callCount(1L)
                .callFailedCount(traceContext.isError() ? 1L : 0L)
                .creditAmount(creditAmount)
                .feeAmount(feeAmount)
                .cacheInputTokens(tokenUsage != null && !traceContext.isError() ? tokenUsage.cacheInputTokens : 0L)
                .inputTokens(tokenUsage != null && !traceContext.isError() ? tokenUsage.inputTokens : 0L)
                .outputTokens(tokenUsage != null && !traceContext.isError() ? tokenUsage.outputTokens : 0L)
                .build();
        log.debug("消费统计已记录 salerId={} type=SALES targetType={} targetId={} dt={}",
                salerId, traceTarget.getTargetType(), traceTarget.getTargetId(), dt);
        iBillResourceStatService.appendStat(salerStat);
    }

    private String targetsToSting(List<TraceContext.TraceTarget> traceTargets) {
        StringBuilder stringBuilder = new StringBuilder();
        for (TraceContext.TraceTarget traceTarget : traceTargets) {
            if (traceTarget.getTargetType() == TraceContext.TraceTargetType.Model) {
                stringBuilder.append("[").append(traceTarget.getDescription()).append("(").append(traceTarget.getName()).append(")").append("]");
            } else {
                stringBuilder.append("[").append(traceTarget.getName()).append("]");
            }
        }
        return stringBuilder.toString();
    }
}
