package com.xspaceagi.system.domain.service;

import com.xspaceagi.system.infra.dao.entity.UserMetric;
import com.xspaceagi.system.spec.enums.PeriodTypeEnum;

import java.math.BigDecimal;
import java.util.List;

public interface UserMetricDomainService {

    /**
     * 新增用户计量数据
     */
    void add(UserMetric userMetric);

    /**
     * 更新用户计量数据
     */
    void update(UserMetric userMetric);

    /**
     * 根据ID查询用户计量数据
     */
    UserMetric queryById(Long id);

    /**
     * 查询指定用户的所有计量数据
     */
    List<UserMetric> queryByUserId(Long userId, String period);

    /**
     * 根据唯一键查询用户计量数据（用户ID + 业务类型 + 时段类型 + 时段值）
     */
    UserMetric queryByUniqueKey(Long tenantId, Long userId, String bizType, PeriodTypeEnum periodType, String period);


    /**
     * 增加计量值（原子操作）
     *
     * @return 是否成功
     */
    boolean incrementValue(Long userId, String bizType, PeriodTypeEnum periodType, String period, BigDecimal delta);

}