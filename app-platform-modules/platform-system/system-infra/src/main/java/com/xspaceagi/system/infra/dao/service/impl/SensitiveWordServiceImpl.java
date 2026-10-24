package com.xspaceagi.system.infra.dao.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.system.infra.dao.entity.SensitiveWord;
import com.xspaceagi.system.infra.dao.mapper.SensitiveWordMapper;
import com.xspaceagi.system.infra.dao.service.SensitiveWordService;
import org.springframework.stereotype.Service;

@Service
public class SensitiveWordServiceImpl extends ServiceImpl<SensitiveWordMapper, SensitiveWord> implements SensitiveWordService {
}
