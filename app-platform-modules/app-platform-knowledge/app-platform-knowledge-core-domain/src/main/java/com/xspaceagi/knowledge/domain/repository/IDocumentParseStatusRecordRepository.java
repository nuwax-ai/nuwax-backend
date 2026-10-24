package com.xspaceagi.knowledge.domain.repository;

import com.xspaceagi.knowledge.domain.model.DocumentParseStatusRecordModel;
import com.xspaceagi.system.spec.common.UserContext;

/**
 * 文档解析状态记录仓储接口
 *
 * @author system
 * @date 2026-05-08
 */
public interface IDocumentParseStatusRecordRepository {

    /**
     * 新增
     *
     * @param model model
     * @param userContext 用户上下文
     * @return id
     */
    Long addInfo(DocumentParseStatusRecordModel model, UserContext userContext);

    /**
     * 更新
     *
     * @param model model
     * @param userContext 用户上下文
     * @return id
     */
    Long updateInfo(DocumentParseStatusRecordModel model, UserContext userContext);
}
