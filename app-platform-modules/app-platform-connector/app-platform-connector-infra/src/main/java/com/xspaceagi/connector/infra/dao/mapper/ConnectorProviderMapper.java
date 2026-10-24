package com.xspaceagi.connector.infra.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xspaceagi.connector.domain.entity.ConnectorProvider;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConnectorProviderMapper extends BaseMapper<ConnectorProvider> {
}
