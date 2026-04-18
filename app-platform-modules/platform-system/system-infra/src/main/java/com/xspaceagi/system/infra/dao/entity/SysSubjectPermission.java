package com.xspaceagi.system.infra.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xspaceagi.system.spec.enums.PermissionSubjectTypeEnum;
import com.xspaceagi.system.spec.enums.PermissionTargetTypeEnum;
import lombok.Data;

import java.util.Date;

/**
 * 主体访问权限
 */
@Data
@TableName(value = "sys_subject_permission", autoResultMap = true)
public class SysSubjectPermission {
    
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 主体类型
     * @see PermissionSubjectTypeEnum
     */
    private Integer subjectType;

    /**
     * 主体ID
     */
    private Long subjectId;

    /**
     * 主体Key
     */
    private String subjectKey;

    /**
     * 目标类型
     * @see PermissionTargetTypeEnum
     */
    private Integer targetType;

    /**
     * 目标ID列表
     */
    private Long targetId;

    /**
     * 配置
     */
    private String config;

    /**
     * 租户ID
     */
    @TableField(value = "_tenant_id")
    private Long tenantId;
    
    /**
     * 创建人ID
     */
    private Long creatorId;
    
    /**
     * 创建人
     */
    private String creator;
    
    /**
     * 创建时间
     */
    private Date created;
    
    /**
     * 修改人ID
     */
    private Long modifierId;
    
    /**
     * 修改人
     */
    private String modifier;
    
    /**
     * 修改时间
     */
    private Date modified;
    
    /**
     * 是否有效；1：有效，-1：无效
     */
    private Integer yn;
}
