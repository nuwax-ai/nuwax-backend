package com.xspaceagi.subscription.infra.dao.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.subscription.infra.dao.entity.UserSubscription;
import com.xspaceagi.subscription.infra.dao.mapper.UserSubscriptionMapper;
import com.xspaceagi.subscription.infra.dao.service.IUserSubscriptionService;
import org.springframework.stereotype.Service;

@Service
public class UserSubscriptionServiceImpl extends ServiceImpl<UserSubscriptionMapper, UserSubscription> implements IUserSubscriptionService {
}
