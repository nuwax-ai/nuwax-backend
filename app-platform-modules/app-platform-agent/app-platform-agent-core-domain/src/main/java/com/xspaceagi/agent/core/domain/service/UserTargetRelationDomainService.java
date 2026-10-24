package com.xspaceagi.agent.core.domain.service;

import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.adapter.repository.entity.UserTargetRelation;

import java.util.List;

public interface UserTargetRelationDomainService {

    boolean record(Long userId, Published.TargetType targetType, UserTargetRelation.OpType opType, Long targetId);

    boolean unRecord(Long userId, Published.TargetType targetType, UserTargetRelation.OpType opType, Long targetId);

    /** 清除目标全部用户的关系记录（目标被删除时清理，返回是否删除了记录） */
    boolean unRecordAll(Published.TargetType targetType, UserTargetRelation.OpType opType, Long targetId);

    List<UserTargetRelation> queryRecentUseList(Long userId, Published.TargetType targetType, Integer size, Integer pageIndex, String targetSubType);

    UserTargetRelation queryRecentUse(Long userId, Published.TargetType targetType, Long targetId);

    List<UserTargetRelation> queryCollectionList(Long userId, Published.TargetType targetType, Integer page, Integer size);

    /** 查询用户全部收藏的目标ID（收藏过滤查询用；个人收藏量小，不分页） */
    List<Long> queryAllCollectionIds(Long userId, Published.TargetType targetType);

    List<UserTargetRelation> queryDevCollectionList(Long userId, Published.TargetType targetType, Integer page, Integer size);

    /**
     * 查询用户启用的目标列表
     */
    List<UserTargetRelation> queryEnableList(Long userId, Published.TargetType targetType, Integer page, Integer size);

    List<UserTargetRelation> queryRecentEditList(Long userId, Published.TargetType targetType, Integer size);

    List<UserTargetRelation> queryUserTargetRelationByTargetIds(Long userId, Published.TargetType targetType, UserTargetRelation.OpType type, List<Long> targetIds);

    void addOrUpdateRecentEdit(UserTargetRelation userTargetRelation);

    void addOrUpdateRecentUsed(UserTargetRelation userTargetRelation);

    void like(Long userId, Published.TargetType targetType, Long targetId);

    void unLike(Long userId, Published.TargetType targetType, Long targetId);

    void delete(Long id);

    /** 按目标对象删除全部用户关系（收藏/最近使用等，项目彻底删除用）；返回删除条数 */
    int deleteByTarget(Published.TargetType targetType, Long targetId);
}
