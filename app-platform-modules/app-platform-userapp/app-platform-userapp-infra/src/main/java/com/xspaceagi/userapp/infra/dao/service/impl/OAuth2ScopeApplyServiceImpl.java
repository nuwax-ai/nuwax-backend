package com.xspaceagi.userapp.infra.dao.service.impl;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.userapp.infra.dao.entity.OAuth2ScopeApply;
import com.xspaceagi.userapp.infra.dao.mapper.OAuth2ScopeApplyMapper;
import com.xspaceagi.userapp.infra.dao.service.IOAuth2ScopeApplyService;

@Service
public class OAuth2ScopeApplyServiceImpl extends ServiceImpl<OAuth2ScopeApplyMapper, OAuth2ScopeApply>
        implements IOAuth2ScopeApplyService {
}
