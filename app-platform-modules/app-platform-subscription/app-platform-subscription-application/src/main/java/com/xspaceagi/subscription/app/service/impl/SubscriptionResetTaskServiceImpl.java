package com.xspaceagi.subscription.app.service.impl;

import com.xspaceagi.subscription.app.service.UserSubscriptionAppService;
import com.xspaceagi.subscription.infra.dao.entity.UserSubscription;
import com.xspaceagi.subscription.infra.dao.service.IUserSubscriptionService;
import com.xspaceagi.subscription.spec.enums.BizTypeEnum;
import com.xspaceagi.subscription.spec.enums.SubscriptionStatusEnum;
import com.xspaceagi.system.sdk.permission.IPermissionCacheRpcSerivce;
import com.xspaceagi.system.sdk.service.AbstractTaskExecuteService;
import com.xspaceagi.system.sdk.service.ScheduleTaskApiService;
import com.xspaceagi.system.sdk.service.dto.ScheduleTaskDto;
import com.xspaceagi.system.spec.common.RequestContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;

@Slf4j
@Service("subscriptionResetTaskService")
public class SubscriptionResetTaskServiceImpl extends AbstractTaskExecuteService {

    @Resource
    private ScheduleTaskApiService scheduleTaskApiService;

    @Resource
    private UserSubscriptionAppService userSubscriptionAppService;

    @Resource
    private IUserSubscriptionService userSubscriptionService;

    @Resource
    private IPermissionCacheRpcSerivce iPermissionCacheRpcSerivce;

    @PostConstruct
    public void init() {
        scheduleTaskApiService.start(ScheduleTaskDto.builder()
                .taskId("subscriptionResetTaskService")
                .beanId("subscriptionResetTaskService")
                .maxExecTimes(Long.MAX_VALUE)
                .cron(ScheduleTaskDto.Cron.EVERY_10_SECOND.getCron())
                .params(Map.of())
                .build());
    }

    @Override
    protected boolean execute(ScheduleTaskDto scheduleTaskDto) {
        userSubscriptionAppService.getNeedResetSubscriptions().forEach(subscription -> {
            try {
                RequestContext.setThreadTenantId(subscription.getTenantId());
                userSubscriptionAppService.reset(subscription);
            } finally {
                RequestContext.remove();
            }
        });
        userSubscriptionAppService.getExpiredSubscriptions().forEach(subscription -> {
            try {
                RequestContext.setThreadTenantId(subscription.getTenantId());
                UserSubscription update = new UserSubscription();
                update.setId(subscription.getId());
                update.setStatus(SubscriptionStatusEnum.EXPIRED.getCode());
                userSubscriptionService.updateById(update);
                if (subscription.getBizType() == BizTypeEnum.SYSTEM) {
                    iPermissionCacheRpcSerivce.clearCacheByTenantAndUserIds(subscription.getTenantId(), Collections.singletonList(subscription.getUserId()));
                    log.info("用户订阅已过期，清除用户权限缓存，用户ID：{}", subscription.getUserId());
                }
            } finally {
                RequestContext.remove();
            }
        });
        return false;
    }
}
