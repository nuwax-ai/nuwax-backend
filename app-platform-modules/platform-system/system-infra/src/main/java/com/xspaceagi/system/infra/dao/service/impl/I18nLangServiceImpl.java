package com.xspaceagi.system.infra.dao.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.system.infra.dao.entity.I18nLang;
import com.xspaceagi.system.infra.dao.mapper.I18nLangMapper;
import com.xspaceagi.system.infra.dao.service.I18nLangService;
import org.springframework.stereotype.Service;

/**
 * 语言表服务实现
 */
@Service
public class I18nLangServiceImpl extends ServiceImpl<I18nLangMapper, I18nLang> implements I18nLangService {
}
