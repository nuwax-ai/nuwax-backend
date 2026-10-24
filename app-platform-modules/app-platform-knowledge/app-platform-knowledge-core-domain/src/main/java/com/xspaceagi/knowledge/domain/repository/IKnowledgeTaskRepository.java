package com.xspaceagi.knowledge.domain.repository;

import com.xspaceagi.knowledge.domain.model.KnowledgeTaskModel;
import com.xspaceagi.system.spec.common.UserContext;

import java.util.List;

public interface IKnowledgeTaskRepository {

    /**
     * 查询最近几天,需要重试的任务,最近5分钟的数据,不进行重试,防止正在运行
     *
     * @param days 最近xx天的数据,进行重试
     * @return
     */
    List<KnowledgeTaskModel> queryListForRetryByDays(Integer days);

    /**
     * 查询最近成功的数据,进行归档
     *
     * @param days 对xx天以前的成功数据,进行归档
     * @return
     */
    List<KnowledgeTaskModel> queryListForArchiveByDaysAndSuccess(Integer days);


    /**
     * 根据文件id,查询对应文档的状态,如果没查到,默认成功(因为成功任务可能被归档了)
     *
     * @param docIds 文档id列表
     * @return 任务列表
     */
    List<KnowledgeTaskModel> queryListByDocIds(List<Long> docIds);


    /**
     * 根据ID集合查询列表
     *
     * @param ids id集合
     * @return 列表
     */
    List<KnowledgeTaskModel> queryListByIds(List<Long> ids);

    /**
     * 根据主键查询 单条记录
     *
     * @param id id
     * @return 单条记录
     */
    KnowledgeTaskModel queryOneInfoById(Long id);

    /**
     * 根据文档id查询
     *
     * @param docId 文档id
     * @return model
     */
    KnowledgeTaskModel queryOneByDocId(Long docId);

    /**
     * 根据文档id查询最新的一条记录（按创建时间倒序）
     *
     * @param docId 文档id
     * @return 最新任务记录
     */
    KnowledgeTaskModel queryLatestOneByDocId(Long docId);

    /**
     * 更新
     *
     * @param model entity
     * @return id
     */
    Long updateInfo(KnowledgeTaskModel model, UserContext userContext);

    /**
     * 新增
     */
    Long addInfo(KnowledgeTaskModel model, UserContext userContext);

    /**
     * 删除根据主键id
     *
     * @param id id
     */
    void deleteById(Long id, UserContext userContext);

    /**
     * 文档id删除
     *
     * @param docId 文档id
     */
    void deleteByDocId(Long docId);

    /**
     * 重试次数加1
     *
     * @param id          id
     * @param userContext
     */
    void incrementRetryCount(Long id, UserContext userContext);

    /**
     * 根据文档id,删除任务
     *
     * @param docIds 文档id列表
     */
    void deleteByDocIds(List<Long> docIds);
}
