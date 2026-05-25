package com.xspaceagi.subscription.app.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.credit.sdk.dto.CreditAddRequest;
import com.xspaceagi.credit.sdk.rpc.ICreditRpcService;
import com.xspaceagi.credit.spec.enums.CreditTypeEnum;
import com.xspaceagi.subscription.app.service.SubscriptionPlanAppService;
import com.xspaceagi.subscription.app.service.UserSubscriptionAppService;
import com.xspaceagi.subscription.infra.dao.entity.Plan;
import com.xspaceagi.subscription.infra.dao.entity.UserSubscription;
import com.xspaceagi.subscription.infra.dao.mapper.UserSubscriptionMapper;
import com.xspaceagi.subscription.infra.dao.service.IPlanService;
import com.xspaceagi.subscription.infra.dao.service.IUserSubscriptionService;
import com.xspaceagi.subscription.sdk.dto.*;
import com.xspaceagi.subscription.spec.enums.BizTypeEnum;
import com.xspaceagi.subscription.spec.enums.PlanPeriodEnum;
import com.xspaceagi.subscription.spec.enums.SubscriptionStatusEnum;
import com.xspaceagi.system.sdk.permission.IPermissionCacheRpcSerivce;
import com.xspaceagi.system.sdk.server.IUserRpcService;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class UserSubscriptionAppServiceImpl implements UserSubscriptionAppService {

    @Resource
    private IUserSubscriptionService userSubscriptionService;

    @Resource
    private UserSubscriptionMapper userSubscriptionMapper;

    @Resource
    private SubscriptionPlanAppService subscriptionPlanAppService;

    @Resource
    private IPlanService planService;

    @Resource
    private ICreditRpcService iCreditRpcService;

    @Resource
    private IUserRpcService iUserRpcService;

    @Resource
    private IPermissionCacheRpcSerivce iPermissionCacheRpcSerivce;

    @Override
    public List<UserSubscriptionDTO> getUserSubscriptions(SubscriptionQueryRequest query) {
        if (query.getBizType() == null) {
            query.setBizType(BizTypeEnum.SYSTEM);
        }
        List<UserSubscription> subscriptions = userSubscriptionService.lambdaQuery()
                .eq(UserSubscription::getUserId, query.getUserId())
                .eq(query.getBizType() != null, UserSubscription::getBizType, query.getBizType())
                .eq(query.getStatus() != null, UserSubscription::getStatus, SubscriptionStatusEnum.getByCode(query.getStatus()))
                .eq(query.getBizId() != null, UserSubscription::getBizId, query.getBizId())
                .list();

        List<UserSubscriptionDTO> result = subscriptions.stream().map((UserSubscription entity) -> convertToDTO(entity, query.isShowPlanDescItems())).collect(Collectors.toCollection(ArrayList::new));

        //是否包含可用订阅
        boolean hasActiveSubscription = result.stream().anyMatch(sub -> sub.getPlan() != null && sub.getStatus() == SubscriptionStatusEnum.ACTIVE);
        boolean shouldAddFreePlan = (BizTypeEnum.SYSTEM.equals(query.getBizType()) && !hasActiveSubscription)
                || (!BizTypeEnum.SYSTEM.equals(query.getBizType()) && query.getBizId() != null && !hasActiveSubscription);
        if (shouldAddFreePlan) {
            PlanDTO freePlan = subscriptionPlanAppService.getFreePlan(query.getBizType(), query.getBizId(), query.isShowPlanDescItems());
            if (freePlan != null) {
                result.add(buildFreePlanDTO(freePlan));
            }
        }
        return result;
    }

    @Override
    public UserSubscriptionDTO getUserCurrentSubscription(Long userId, BizTypeEnum bizType, String bizId) {
        SubscriptionQueryRequest request = new SubscriptionQueryRequest();
        request.setUserId(userId);
        request.setBizType(bizType);
        request.setBizId(bizId);
        request.setStatus(SubscriptionStatusEnum.ACTIVE.getCode());
        return getUserSubscriptions(request).stream().findFirst().orElse(null);
    }

    @Override
    public UserSubscriptionDTO getUserCurrentSystemSubscription(Long userId, boolean showPlanDescItems) {
        SubscriptionQueryRequest request = new SubscriptionQueryRequest();
        request.setUserId(userId);
        request.setBizType(BizTypeEnum.SYSTEM);
        request.setStatus(SubscriptionStatusEnum.ACTIVE.getCode());
        return getUserSubscriptions(request).stream().findFirst().orElse(null);
    }

    @Override
    public SubscriptionStatsDTO getSubscriptionStats(SubscriptionStatsQueryRequest request) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        Date todayStart = cal.getTime();

        cal.set(Calendar.DAY_OF_MONTH, 1);
        Date monthStart = cal.getTime();

        Map<String, Object> stats = userSubscriptionMapper.selectStatsByPlanBiz(
                request.getBizType().getCode(), request.getBizId(), todayStart, monthStart);

        SubscriptionStatsDTO dto = new SubscriptionStatsDTO();
        dto.setTotalCount(toLong(stats.get("totalCount")));
        dto.setTodayCount(toLong(stats.get("todayCount")));
        dto.setMonthCount(toLong(stats.get("monthCount")));

        Long total = userSubscriptionMapper.countByPlanBiz(
                request.getBizType().getCode(), request.getBizId());
        int offset = (request.getPageNum() - 1) * request.getPageSize();
        List<UserSubscription> subscribers = userSubscriptionMapper.selectByPlanBiz(
                request.getBizType().getCode(), request.getBizId(), offset, request.getPageSize());

        dto.setSubscribers(subscribers.stream().map((UserSubscription entity) -> convertToDTO(entity, true)).collect(Collectors.toList()));
        dto.setTotal(total);
        dto.setPageNum(request.getPageNum());
        dto.setPageSize(request.getPageSize());

        //补充用户信息
        List<Long> userIds = dto.getSubscribers().stream().map(UserSubscriptionDTO::getUserId).toList();
        List<UserContext> userContexts = iUserRpcService.queryUserListByIds(userIds);
        Map<Long, UserContext> userContextMap = userContexts.stream().collect(Collectors.toMap(UserContext::getUserId, userContext -> userContext, (a, b) -> a));
        dto.getSubscribers().forEach(subscriber -> {
            UserContext userContext = userContextMap.get(subscriber.getUserId());
            if (userContext != null) {
                String userName = StringUtils.isNotBlank(userContext.getNickName()) ? userContext.getNickName() : userContext.getUserName();
                subscriber.setSubscriber(new UserSubscriptionDTO.Subscriber(userContext.getUserId(), userName, userContext.getAvatar()));
            }
        });
        return dto;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserSubscriptionDTO createSubscription(CreateSubscriptionRequest request) {
        Plan plan = planService.getById(request.getPlanId());
        if (plan == null) {
            throw new BizException("PLAN_NOT_FOUND", "订阅计划不存在");
        }
        if (plan.getStatus() == null || plan.getStatus() == 0) {
            throw new BizException("PLAN_NOT_AVAILABLE", "订阅计划已下线");
        }

        UserSubscription existingSub = userSubscriptionMapper.selectByUserIdAndPlanId(
                request.getUserId(), request.getPlanId());

        if (existingSub == null) {
            // Check if user has a subscription to a different plan (switch plan)
            UserSubscription otherSub = userSubscriptionService.lambdaQuery()
                    .eq(UserSubscription::getBizType, plan.getBizType())
                    .eq(!plan.getBizType().equals(BizTypeEnum.SYSTEM.getCode()), UserSubscription::getBizId, plan.getBizId())
                    .eq(UserSubscription::getUserId, request.getUserId())
                    .one();
            if (otherSub == null) {
                return handleNewSubscription(request, plan);
            }
            existingSub = otherSub;
        }

        boolean isSamePlan = existingSub.getPlanId().equals(request.getPlanId());
        existingSub.setExtra(request.getExtraJsonString());
        if (isSamePlan) {
            if (request.getExtraJsonString() != null) {
                existingSub.setPeriod(plan.getPeriod());
                userSubscriptionService.updateById(existingSub);
            }
            if (SubscriptionStatusEnum.ACTIVE.getCode().equals(existingSub.getStatus()) && existingSub.getEndTime() != null
                    && existingSub.getEndTime().getTime() > System.currentTimeMillis()) {
                return handleRenewActive(existingSub, plan);
            } else {
                return handleRenewExpired(existingSub, plan);
            }
        } else {
            try {
                return handleChangePlan(existingSub, plan);
            } finally {
                //清除用户权限缓存
                if (plan.getBizType().equals(BizTypeEnum.SYSTEM.getCode())) {
                    iPermissionCacheRpcSerivce.clearCacheByTenantAndUserIds(plan.getTenantId(), List.of(request.getUserId()));
                }
            }
        }
    }

    private UserSubscriptionDTO handleNewSubscription(CreateSubscriptionRequest request, Plan plan) {
        Date startTime = new Date();
        Date endTime = PlanPeriodEnum.FOREVER.getCode().equals(plan.getPeriod()) ? addMonths(startTime, 1200) : addMonths(startTime, plan.getPeriod());
        UserSubscription sub = UserSubscription.builder()
                .userId(request.getUserId())
                .tenantId(plan.getTenantId())
                .planId(plan.getId())
                .bizType(BizTypeEnum.valueOf(plan.getBizType()))
                .bizId(plan.getBizId())
                .period(plan.getPeriod())
                .startTime(startTime)
                .endTime(endTime)
                .status(SubscriptionStatusEnum.ACTIVE.getCode())
                .callUsedCount(0)
                .nextResetTime(startTime)
                .extra(request.getExtraJsonString())
                .build();
        userSubscriptionService.save(sub);
        UserSubscriptionDTO userSubscriptionDTO = convertToDTO(sub, false);
        reset(userSubscriptionDTO);//积分发放
        log.info("新建订阅, userId={}, planId={}, endTime={}", request.getUserId(), plan.getId(), endTime);
        return convertToDTO(sub, false);
    }

    private UserSubscriptionDTO handleRenewActive(UserSubscription sub, Plan plan) {
        Date newEndTime = PlanPeriodEnum.FOREVER.getCode().equals(plan.getPeriod()) ? addMonths(sub.getEndTime(), 1200) : addMonths(sub.getEndTime(), plan.getPeriod());
        userSubscriptionMapper.updateEndTime(sub.getId(), newEndTime);
        sub.setEndTime(newEndTime);

        log.info("续费(生效中), userId={}, planId={}, newEndTime={}", sub.getUserId(), plan.getId(), newEndTime);
        return convertToDTO(sub, false);
    }

    private UserSubscriptionDTO handleRenewExpired(UserSubscription sub, Plan plan) {
        Date startTime = new Date();
        Date endTime = PlanPeriodEnum.FOREVER.getCode().equals(plan.getPeriod()) ? addMonths(startTime, 1200) : addMonths(startTime, plan.getPeriod());
        userSubscriptionMapper.updatePeriod(sub.getId(), startTime, endTime,
                SubscriptionStatusEnum.ACTIVE.getCode());
        sub.setStartTime(startTime);
        sub.setEndTime(endTime);
        sub.setStatus(SubscriptionStatusEnum.ACTIVE.getCode());

        log.info("续费(已过期/已取消), userId={}, planId={}, endTime={}", sub.getUserId(), plan.getId(), endTime);
        UserSubscriptionDTO userSubscriptionDTO = convertToDTO(sub, false);
        reset(userSubscriptionDTO);//积分发放
        return userSubscriptionDTO;
    }

    //更新计划，降级不退，升级补差价
    private UserSubscriptionDTO handleChangePlan(UserSubscription oldSub, Plan newPlan) {
        userSubscriptionMapper.deleteById(oldSub.getId());
        Date startTime = new Date();
        Date endTime = PlanPeriodEnum.FOREVER.getCode().equals(newPlan.getPeriod()) ? addMonths(startTime, 1200) : addMonths(startTime, newPlan.getPeriod());
        UserSubscription sub = UserSubscription.builder()
                .userId(oldSub.getUserId())
                .tenantId(newPlan.getTenantId())
                .planId(newPlan.getId())
                .bizType(BizTypeEnum.valueOf(newPlan.getBizType()))
                .bizId(newPlan.getBizId())
                .period(newPlan.getPeriod())
                .startTime(startTime)
                .endTime(endTime)
                .status(SubscriptionStatusEnum.ACTIVE.getCode())
                .callUsedCount(0)
                .nextResetTime(startTime) // 后台任务会执行初始化积分发放以及更新下次重置时间
                .extra(oldSub.getExtra())
                .build();
        userSubscriptionService.save(sub);
        UserSubscriptionDTO userSubscriptionDTO = convertToDTO(sub, false);
        reset(userSubscriptionDTO);// 积分发放
        return userSubscriptionDTO;
    }

    private UserSubscriptionDTO buildFreePlanDTO(PlanDTO plan) {
        UserSubscriptionDTO dto = new UserSubscriptionDTO();
        dto.setId(0L);
        dto.setTenantId(plan.getTenantId());
        dto.setPlanId(plan.getId());
        dto.setPlanName(plan.getName());
        dto.setBizType(plan.getBizType());
        dto.setBizId(plan.getBizId());
        dto.setPeriod(plan.getPeriod());
        dto.setStartTime(new Date());
        dto.setEndTime(addMonths(dto.getStartTime(), plan.getPeriod().getCode() == 0 ? 1200 : plan.getPeriod().getCode()));
        dto.setStatus(SubscriptionStatusEnum.ACTIVE);
        dto.setPlan(plan);
        return dto;
    }

    private Date addMonths(Date from, int months) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(from);
        cal.add(Calendar.MONTH, months);
        return cal.getTime();
    }

    private Long toLong(Object val) {
        if (val == null) return 0L;
        if (val instanceof Long) return (Long) val;
        if (val instanceof Number) return ((Number) val).longValue();
        return Long.parseLong(val.toString());
    }

    private UserSubscriptionDTO convertToDTO(UserSubscription entity, boolean showPlanDescItems) {
        UserSubscriptionDTO dto = new UserSubscriptionDTO();
        dto.setId(entity.getId());
        dto.setUserId(entity.getUserId());
        dto.setPlanId(entity.getPlanId());
        dto.setStartTime(entity.getStartTime());
        dto.setEndTime(entity.getEndTime());
        dto.setStatus(SubscriptionStatusEnum.getByCode(entity.getStatus()));
        dto.setCreated(entity.getCreated());
        dto.setModified(entity.getModified());
        if (dto.getStatus() == SubscriptionStatusEnum.ACTIVE && entity.getEndTime().getTime() < System.currentTimeMillis()) {
            dto.setStatus(SubscriptionStatusEnum.EXPIRED);
            if (entity.getId() != null) {
                UserSubscription update = new UserSubscription();
                update.setId(entity.getId());
                update.setStatus(SubscriptionStatusEnum.EXPIRED.getCode());
                userSubscriptionService.updateById(update);
                iPermissionCacheRpcSerivce.clearCacheByTenantAndUserIds(entity.getTenantId(), Collections.singletonList(entity.getUserId()));
            }
        }

        dto.setCallUsedCount(entity.getCallUsedCount());
        dto.setNextResetTime(entity.getNextResetTime());
        PlanDTO plan = subscriptionPlanAppService.getPlanById(entity.getPlanId(), showPlanDescItems);
        dto.setPlan(plan);
        if (dto.getPlan() == null && JSON.isValidObject(entity.getExtra())) {
            JSONObject jsonObject = JSON.parseObject(entity.getExtra());
            plan = jsonObject.getObject("plan", PlanDTO.class);
            dto.setPlan(plan);
        }
        dto.setPlanName(dto.getPlan().getName());
        dto.setPeriod(PlanPeriodEnum.getByCode(entity.getPeriod()));
        dto.setBizType(entity.getBizType());
        dto.setBizId(entity.getBizId());
        dto.setTenantId(entity.getTenantId());
        return dto;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reset(UserSubscriptionDTO subscriptionDTO) {
        if (subscriptionDTO.getEndTime() != null && subscriptionDTO.getEndTime().getTime() < System.currentTimeMillis()) {
            log.info("用户订阅已过期, userId={}, planId={}", subscriptionDTO.getUserId(), subscriptionDTO.getPlanId());
            return;
        }
        if (subscriptionDTO.getNextResetTime() != null && subscriptionDTO.getNextResetTime().getTime() > System.currentTimeMillis()) {
            log.info("用户订阅未到重置时间, userId={}, planId={}", subscriptionDTO.getUserId(), subscriptionDTO.getPlanId());
            return;
        }
        log.info("用户订阅重置, userId={}, planId={}", subscriptionDTO.getUserId(), subscriptionDTO.getPlanId());
        Date nextResetTime = addMonths(subscriptionDTO.getStartTime(), 1);
        while (nextResetTime.getTime() < System.currentTimeMillis()) {
            nextResetTime = addMonths(nextResetTime, 1);
        }
        if (subscriptionDTO.getBizType() == BizTypeEnum.SYSTEM && subscriptionDTO.getPlan() != null && subscriptionDTO.getPlan().getCreditAmount().compareTo(BigDecimal.ZERO) > 0) {
            CreditAddRequest creditAddRequest = new CreditAddRequest();
            creditAddRequest.setUserId(subscriptionDTO.getUserId());
            creditAddRequest.setAmount(subscriptionDTO.getPlan().getCreditAmount());
            creditAddRequest.setCreditType(CreditTypeEnum.SUBSCRIPTION);
            creditAddRequest.setRemark("订阅积分发放");
            creditAddRequest.setBizNo("sub" + UUID.randomUUID().toString().replace("-", ""));
            creditAddRequest.setTenantId(subscriptionDTO.getTenantId());
            iCreditRpcService.addCredit(creditAddRequest);
        }
        userSubscriptionMapper.resetCallCount(subscriptionDTO.getId(), nextResetTime);
        log.info("用户订阅重置成功, userId={}, planId={}", subscriptionDTO.getUserId(), subscriptionDTO.getPlanId());
    }

    @Override
    public int incrementCallCount(Long subscriptionId, Integer count) {
        int actualCount = count != null ? count : 1;
        userSubscriptionMapper.incrementCallCount(subscriptionId, actualCount);
        return actualCount;
    }

    @Override
    public List<UserSubscriptionDTO> getNeedResetSubscriptions() {
        List<UserSubscription> subscriptions = TenantFunctions.callWithIgnoreCheck(() -> userSubscriptionService.lambdaQuery()
                .in(UserSubscription::getBizType, BizTypeEnum.SYSTEM.getCode(), BizTypeEnum.AGENT.getCode())
                .le(UserSubscription::getNextResetTime, new Date())
                .last("limit 1000")
                .list());

        return TenantFunctions.callWithIgnoreCheck(() -> subscriptions.stream().map((UserSubscription entity) -> convertToDTO(entity, false)).collect(Collectors.toCollection(ArrayList::new)));
    }

    @Override
    public List<UserSubscriptionDTO> getExpiredSubscriptions() {
        List<UserSubscription> subscriptions = TenantFunctions.callWithIgnoreCheck(() -> userSubscriptionService.lambdaQuery()
                .ne(UserSubscription::getStatus, SubscriptionStatusEnum.EXPIRED.getCode())
                .le(UserSubscription::getEndTime, new Date())
                .last("limit 1000")
                .list());
        return TenantFunctions.callWithIgnoreCheck(() -> subscriptions.stream().map((UserSubscription entity) -> convertToDTO(entity, false)).collect(Collectors.toCollection(ArrayList::new)));
    }
}
