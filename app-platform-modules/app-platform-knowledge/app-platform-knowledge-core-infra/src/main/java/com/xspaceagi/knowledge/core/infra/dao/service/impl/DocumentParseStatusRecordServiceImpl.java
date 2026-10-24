package com.xspaceagi.knowledge.core.infra.dao.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.knowledge.core.infra.dao.mapper.DocumentParseStatusRecordMapper;
import com.xspaceagi.knowledge.core.infra.dao.service.DocumentParseStatusRecordService;
import com.xspaceagi.knowledge.core.infra.knowledgegraph.repository.entity.DocumentParseStatusRecord;
import org.springframework.stereotype.Service;

/**
 * 文档解析状态记录 Service 实现
 *
 * @author system
 * @date 2026-05-08
 */
@Service
public class DocumentParseStatusRecordServiceImpl extends ServiceImpl<DocumentParseStatusRecordMapper, DocumentParseStatusRecord>
        implements DocumentParseStatusRecordService {

    @Override
    public Long addInfo(DocumentParseStatusRecord entity) {
        this.save(entity);
        return entity.getId().longValue();
    }

    @Override
    public Long updateInfo(DocumentParseStatusRecord entity) {
        this.updateById(entity);
        return entity.getId().longValue();
    }
}
