package com.xspaceagi.knowledge.core.infra.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xspaceagi.knowledge.core.infra.dao.entity.KnowledgeAccuracyTest;

/**
 * 知识库准确性测试历史 Mapper
 *
 * @author system
 * @description 针对表【knowledge_recall_verification(知识库准确性测试历史表)】的数据库操作Mapper
 * @Entity com.xspaceagi.knowledge.core.infra.dao.entity.KnowledgeAccuracyTest
 */
public interface KnowledgeAccuracyTestMapper extends BaseMapper<KnowledgeAccuracyTest> {
    // 继承BaseMapper，获得基本的CRUD能力
    // 分页查询使用MyBatis-Plus的Page对象
}
