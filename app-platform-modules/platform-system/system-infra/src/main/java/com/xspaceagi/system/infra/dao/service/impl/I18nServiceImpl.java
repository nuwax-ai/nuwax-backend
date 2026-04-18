package com.xspaceagi.system.infra.dao.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.system.infra.dao.entity.I18nConfig;
import com.xspaceagi.system.infra.dao.mapper.I18nMapper;
import com.xspaceagi.system.infra.dao.service.I18nService;
import org.springframework.stereotype.Service;

@Service
public class I18nServiceImpl extends ServiceImpl<I18nMapper, I18nConfig> implements I18nService {
}
