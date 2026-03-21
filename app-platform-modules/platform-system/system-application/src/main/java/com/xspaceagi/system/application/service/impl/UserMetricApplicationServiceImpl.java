package com.xspaceagi.system.application.service.impl;

import com.xspaceagi.system.application.dto.UserMetricDto;
import com.xspaceagi.system.application.service.UserMetricApplicationService;
import com.xspaceagi.system.domain.service.UserMetricDomainService;
import com.xspaceagi.system.infra.dao.entity.UserMetric;
import jakarta.annotation.Resource;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class UserMetricApplicationServiceImpl implements UserMetricApplicationService {

    @Resource
    private UserMetricDomainService userMetricDomainService;

    @Override
    public List<UserMetricDto> queryByUserId(Long userId, String period) {
        List<UserMetric> userMetrics = userMetricDomainService.queryByUserId(userId, period);
        return userMetrics.stream()
                .map(this::convert)
                .collect(Collectors.toList());
    }

    private UserMetricDto convert(UserMetric userMetric) {
        if (userMetric == null) {
            return null;
        }
        UserMetricDto dto = new UserMetricDto();
        BeanUtils.copyProperties(userMetric, dto);
        return dto;
    }
}
