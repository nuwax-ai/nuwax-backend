package com.xspaceagi.system.domain.service;

import com.xspaceagi.system.infra.dao.entity.SysSubjectPermission;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.enums.PermissionSubjectTypeEnum;
import com.xspaceagi.system.spec.enums.PermissionTargetTypeEnum;

import java.util.List;
import java.util.Map;

public interface SysSubjectPermissionDomainService {

    /**
     * 查询指定主体的权限配置（若返回空，表示该主体不限制访问）
     */
    List<SysSubjectPermission> getBySubject(PermissionSubjectTypeEnum subjectType, Long subjectId);

    /**
     * 查询指定 target（角色/用户组）绑定的主体ID列表
     */
    List<Long> listSubjectIdsByTarget(PermissionTargetTypeEnum targetType, Long targetId, PermissionSubjectTypeEnum subjectType);

    /**
     * 获取指定 target（角色/用户组）绑定的主体KEY列表
     */
    List<String> listSubjectKeysByTarget(PermissionTargetTypeEnum targetType, Long targetId, PermissionSubjectTypeEnum subjectType);

    /**
     * 获取指定 target（角色/用户组）绑定的主体KEY和配置
     */
    Map<String, String> listSubjectKeyConfigByTarget(PermissionTargetTypeEnum targetType, Long targetId, PermissionSubjectTypeEnum subjectType);

    /**
     * 全量覆盖：设置 target（角色/用户组）在某 subjectType 下绑定的 subjectIds
     * <p>
     * 传空/空集合表示清空绑定（即该 target 不再拥有任何受限主体的访问权限）
     * </p>
     */
    void replaceSubjectsByTargetAndSubjectIds(PermissionTargetTypeEnum targetType, Long targetId,
                                              PermissionSubjectTypeEnum subjectType, List<Long> subjectIds,
                                              UserContext userContext);

    /**
     * 全量覆盖：设置 target（角色/用户组）在某 subjectType 下绑定的 subjectKey 配置
     */
    void replaceSubjectsByTargetAndSubjectKeyConfig(PermissionTargetTypeEnum targetType, Long targetId,
                                                    PermissionSubjectTypeEnum subjectType, Map<String, String> subjectKeyConfigMap,
                                                    UserContext userContext);

    /**
     * 为主体增加一个目标（角色/用户组）访问权限
     */
    void addTarget(PermissionSubjectTypeEnum subjectType, Long subjectId, String subjectKey,
                   PermissionTargetTypeEnum targetType, Long targetId,
                   UserContext userContext);

    /**
     * 为主体移除一个目标（角色/用户组）访问权限
     * 若移除后该主体在该 targetType 下无任何目标，则删除配置记录（恢复为不限制访问）
     */
    void removeTarget(PermissionSubjectTypeEnum subjectType, Long subjectId,
                      PermissionTargetTypeEnum targetType, Long targetId,
                      UserContext userContext);

    /**
     * 全量覆盖：设置主体（subject）的可访问目标（角色/用户组）
     * <p>
     * 传空/空集合表示清空绑定（即该主体不限制访问，任何人可访问）
     * </p>
     */
    void replaceTargetsBySubject(PermissionSubjectTypeEnum subjectType, Long subjectId,
                                 List<Long> roleIds, List<Long> groupIds,
                                 UserContext userContext);

}
