package com.xspaceagi.system.application.service;

import com.xspaceagi.system.application.dto.permission.BindRestrictionTargetsDto;
import com.xspaceagi.system.application.dto.permission.SubjectTargetsDto;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.enums.PermissionSubjectTypeEnum;
import com.xspaceagi.system.spec.enums.PermissionTargetTypeEnum;

import java.util.List;
import java.util.Map;

/**
 * 主体访问权限应用服务
 */
public interface SysSubjectPermissionApplicationService {

    /**
     * 查询指定 target（角色/用户组）绑定的主体ID列表
     */
    List<Long> listSubjectIdsByTarget(PermissionTargetTypeEnum targetType, Long targetId, PermissionSubjectTypeEnum subjectType);

    /**
     * 查询指定 target（角色/用户组）绑定的主体KEY列表
     */
    List<String> listSubjectKeysByTarget(PermissionTargetTypeEnum targetType, Long targetId, PermissionSubjectTypeEnum subjectType);

    /**
     * 查询指定 target（角色/用户组）绑定的主体KEY和配置
     */
    Map<String, String> listSubjectKeyConfigByTarget(PermissionTargetTypeEnum targetType, Long targetId, PermissionSubjectTypeEnum subjectType);

    /**
     * 查询指定主体配置的访问对象（角色、用户组）
     */
    SubjectTargetsDto listTargetsBySubject(PermissionSubjectTypeEnum subjectType, Long subjectId);

    /**
     * 绑定主体限制访问对象（全量覆盖）
     */
    void bindRestrictionTargets(PermissionSubjectTypeEnum subjectType, Long subjectId,
                                BindRestrictionTargetsDto bindDto, UserContext userContext);
}
