package com.xspaceagi.userapp.infra.dao.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xspaceagi.userapp.infra.dao.entity.OAuth2ScopeApply;

@Mapper
public interface OAuth2ScopeApplyMapper extends BaseMapper<OAuth2ScopeApply> {
}
