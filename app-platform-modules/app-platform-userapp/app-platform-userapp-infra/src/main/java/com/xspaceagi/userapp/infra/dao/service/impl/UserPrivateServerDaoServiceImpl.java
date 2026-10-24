package com.xspaceagi.userapp.infra.dao.service.impl;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.userapp.infra.dao.entity.UserPrivateServer;
import com.xspaceagi.userapp.infra.dao.mapper.UserPrivateServerMapper;
import com.xspaceagi.userapp.infra.dao.service.IUserPrivateServerDaoService;

@Service
public class UserPrivateServerDaoServiceImpl extends ServiceImpl<UserPrivateServerMapper, UserPrivateServer>
        implements IUserPrivateServerDaoService {
}
