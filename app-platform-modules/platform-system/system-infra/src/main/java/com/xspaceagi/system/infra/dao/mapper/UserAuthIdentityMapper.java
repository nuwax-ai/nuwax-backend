package com.xspaceagi.system.infra.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xspaceagi.system.infra.dao.entity.UserAuthIdentity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserAuthIdentityMapper extends BaseMapper<UserAuthIdentity> {
}
