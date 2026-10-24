package com.xspaceagi.knowledge.core.infra.dao.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.xspaceagi.knowledge.core.infra.knowledgegraph.repository.entity.DocumentParseStatusRecord;

/**
 * 文档解析状态记录 Service
 *
 * @author system
 * @date 2026-05-08
 */
public interface DocumentParseStatusRecordService extends IService<DocumentParseStatusRecord> {

    /**
     * 新增
     *
     * @param entity entity
     * @return id
     */
    Long addInfo(DocumentParseStatusRecord entity);

    /**
     * 更新
     *
     * @param entity entity
     * @return id
     */
    Long updateInfo(DocumentParseStatusRecord entity);
}
