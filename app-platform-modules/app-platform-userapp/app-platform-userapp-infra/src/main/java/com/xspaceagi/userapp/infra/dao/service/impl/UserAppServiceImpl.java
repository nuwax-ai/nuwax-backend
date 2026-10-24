package com.xspaceagi.userapp.infra.dao.service.impl;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.userapp.infra.dao.entity.UserApp;
import com.xspaceagi.userapp.infra.dao.mapper.UserAppMapper;
import com.xspaceagi.userapp.infra.dao.service.IUserAppService;

@Service
public class UserAppServiceImpl extends ServiceImpl<UserAppMapper, UserApp>
        implements IUserAppService {
}
