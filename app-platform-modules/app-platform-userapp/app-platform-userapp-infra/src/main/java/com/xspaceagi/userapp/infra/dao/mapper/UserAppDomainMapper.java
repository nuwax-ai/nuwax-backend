package com.xspaceagi.userapp.infra.dao.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xspaceagi.userapp.infra.dao.entity.UserAppDomain;

@Mapper
public interface UserAppDomainMapper extends BaseMapper<UserAppDomain> {
}
