package com.xspaceagi.im.domain.repository.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.im.domain.repository.ImChannelConfigRepository;
import com.xspaceagi.im.infra.dao.enitity.ImChannelConfig;
import com.xspaceagi.im.infra.dao.mapper.ImChannelConfigMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ImChannelConfigRepositoryImpl
        extends ServiceImpl<ImChannelConfigMapper, ImChannelConfig>
        implements ImChannelConfigRepository {
}
