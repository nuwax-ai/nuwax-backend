package com.xspaceagi.userapp.infra.dao.service.impl;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.userapp.infra.dao.entity.UserAppTask;
import com.xspaceagi.userapp.infra.dao.mapper.UserAppTaskMapper;
import com.xspaceagi.userapp.infra.dao.service.IUserAppTaskService;

@Service
public class UserAppTaskServiceImpl extends ServiceImpl<UserAppTaskMapper, UserAppTask>
        implements IUserAppTaskService {
}
