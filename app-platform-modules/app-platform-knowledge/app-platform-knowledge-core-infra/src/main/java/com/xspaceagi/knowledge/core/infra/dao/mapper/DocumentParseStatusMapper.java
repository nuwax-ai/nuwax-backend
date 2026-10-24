package com.xspaceagi.knowledge.core.infra.dao.mapper;

import com.xspaceagi.knowledge.core.infra.knowledgegraph.repository.entity.DocumentParseStatus;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @author system
 * @description 针对表【document_parse_status(文档三元组解析状态表)】的数据库操作Mapper
 * @createDate 2026-04-16
 * @Entity com.xspaceagi.knowledge.core.infra.knowledgegraph.repository.entity.DocumentParseStatus
 */
public interface DocumentParseStatusMapper extends BaseMapper<DocumentParseStatus> {
}
