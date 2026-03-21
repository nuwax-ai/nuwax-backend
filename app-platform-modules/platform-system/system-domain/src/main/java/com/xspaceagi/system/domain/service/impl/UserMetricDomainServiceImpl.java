package com.xspaceagi.system.domain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xspaceagi.system.domain.service.UserMetricDomainService;
import com.xspaceagi.system.infra.dao.entity.UserMetric;
import com.xspaceagi.system.infra.dao.service.UserMetricService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.PeriodTypeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

@Slf4j
@Service
public class UserMetricDomainServiceImpl implements UserMetricDomainService {

    @Resource
    private UserMetricService userMetricService;

    @Override
    public void add(UserMetric userMetric) {
        userMetric.setCreated(new Date());
        userMetric.setModified(new Date());
        userMetricService.save(userMetric);
    }

    @Override
    public void update(UserMetric userMetric) {
        userMetric.setModified(new Date());
        userMetricService.updateById(userMetric);
    }

    @Override
    public UserMetric queryById(Long id) {
        return userMetricService.getById(id);
    }

    @Override
    public List<UserMetric> queryByUserId(Long userId, String period) {
        LambdaQueryWrapper<UserMetric> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(UserMetric::getUserId, userId);
        queryWrapper.eq(UserMetric::getPeriod, period);
        queryWrapper.orderByDesc(UserMetric::getPeriodType)
                .orderByDesc(UserMetric::getPeriod);
        return userMetricService.list(queryWrapper);
    }

    @Override
    public UserMetric queryByUniqueKey(Long tenantId, Long userId, String bizType, PeriodTypeEnum periodType, String period) {
        LambdaQueryWrapper<UserMetric> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(UserMetric::getTenantId, tenantId);
        queryWrapper.eq(UserMetric::getUserId, userId);
        queryWrapper.eq(UserMetric::getBizType, bizType);
        queryWrapper.eq(UserMetric::getPeriodType, periodType.getCode());
        queryWrapper.eq(UserMetric::getPeriod, period);
        return userMetricService.getOne(queryWrapper, false);
    }

    @Override
    public boolean incrementValue(Long userId, String bizType, PeriodTypeEnum periodType, String period, BigDecimal delta) {
        UserMetric userMetric = queryByUniqueKey(RequestContext.get().getTenantId(), userId, bizType, periodType, period);
        if (userMetric == null) {
            userMetric = new UserMetric();
            userMetric.setUserId(userId);
            userMetric.setBizType(bizType);
            userMetric.setPeriodType(periodType.getCode());
            userMetric.setPeriod(period);
            userMetric.setValue(delta);
            userMetric.setCreated(new Date());
            userMetric.setModified(new Date());
            try {
                return userMetricService.save(userMetric);
            } catch (Exception e) {
                log.debug("save user metric error: {}", e.getMessage());
                return false;
            }
        } else {
            userMetric.setValue(userMetric.getValue().add(delta));
            userMetric.setModified(new Date());
            return userMetricService.updateById(userMetric);
        }
    }
}