package com.xspaceagi.userapp.infra.dao.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.userapp.infra.dao.entity.UserProjectWorkspace;
import com.xspaceagi.userapp.infra.dao.mapper.UserProjectWorkspaceMapper;
import com.xspaceagi.userapp.infra.dao.service.IUserProjectWorkspaceService;

import org.springframework.stereotype.Service;

@Service
public class UserProjectWorkspaceServiceImpl extends ServiceImpl<UserProjectWorkspaceMapper, UserProjectWorkspace>
        implements IUserProjectWorkspaceService {
}
