package com.xspaceagi.userapp.infra.dao.service.impl;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.userapp.infra.dao.entity.UserProject;
import com.xspaceagi.userapp.infra.dao.mapper.UserProjectMapper;
import com.xspaceagi.userapp.infra.dao.service.IUserProjectService;

@Service
public class UserProjectServiceImpl extends ServiceImpl<UserProjectMapper, UserProject>
        implements IUserProjectService {
}
