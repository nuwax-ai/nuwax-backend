package com.xspaceagi.im.domain.repository.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.im.domain.repository.ImSessionRepository;
import com.xspaceagi.im.infra.dao.enitity.ImSession;
import com.xspaceagi.im.infra.dao.mapper.ImSessionMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ImSessionRepositoryImpl extends ServiceImpl<ImSessionMapper, ImSession> implements ImSessionRepository {

}
