package com.xspaceagi.knowledge.core.infra.dao.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.xspaceagi.knowledge.core.infra.dao.entity.KnowledgeTask;

import java.util.List;

/**
 * @author soddy
 * @description 针对表【knowledge_task(知识库-定时任务)】的数据库操作Service
 * @createDate 2025-02-21 11:02:01
 */
public interface KnowledgeTaskService extends IService<KnowledgeTask> {
    /**
     * 根据文件id,查询对应文档的状态,如果没查到,默认成功(因为成功任务可能被归档了)
     *
     * @param docIds 文档id列表
     * @return 任务列表
     */
    List<KnowledgeTask> queryListByDocIds(List<Long> docIds);


    /**
     * 根据ID集合查询列表
     *
     * @param ids id集合
     * @return 列表
     */
    List<KnowledgeTask> queryListByIds(List<Long> ids);

    /**
     * 根据主键查询 单条记录
     *
     * @param id id
     * @return 单条记录
     */
    KnowledgeTask queryOneInfoById(Long id);

    /**
     * 根据文档id查询
     *
     * @param docId 文档id
     * @return model
     */
    KnowledgeTask queryOneByDocId(Long docId);

    /**
     * 根据文档id查询最新的一条记录（按创建时间倒序）
     *
     * @param docId 文档id
     * @return 最新任务记录
     */
    KnowledgeTask queryLatestOneByDocId(Long docId);


    /**
     * 更新
     *
     * @param entity entity
     * @return id
     */
    Long updateInfo(KnowledgeTask entity);

    /**
     * 新增
     */
    Long addInfo(KnowledgeTask entity);

    /**
     * 删除根据主键id
     *
     * @param id id
     */
    void deleteById(Long id);


    /**
     * 文档id删除
     *
     * @param docId 文档id
     */
    void deleteByDocId(Long docId);

    /**
     * 根据文档id列表删除
     *
     * @param docIds 文档id列表
     */
    void deleteByDocIds(List<Long> docIds);


}
