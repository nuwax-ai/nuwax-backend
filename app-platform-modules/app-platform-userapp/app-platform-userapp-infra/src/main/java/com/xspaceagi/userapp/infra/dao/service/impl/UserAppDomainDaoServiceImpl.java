package com.xspaceagi.userapp.infra.dao.service.impl;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.userapp.infra.dao.entity.UserAppDomain;
import com.xspaceagi.userapp.infra.dao.mapper.UserAppDomainMapper;
import com.xspaceagi.userapp.infra.dao.service.IUserAppDomainDaoService;

@Service
public class UserAppDomainDaoServiceImpl extends ServiceImpl<UserAppDomainMapper, UserAppDomain>
        implements IUserAppDomainDaoService {
}
