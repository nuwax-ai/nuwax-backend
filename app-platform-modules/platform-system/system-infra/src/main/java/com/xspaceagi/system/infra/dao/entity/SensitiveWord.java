package com.xspaceagi.system.infra.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 敏感词管控
 */
@Data
@TableName(value = "sensitive_word", autoResultMap = true)
public class SensitiveWord {

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 租户ID
     */
    @TableField(value = "_tenant_id")
    private Long tenantId;

    /**
     * 敏感词（包含匹配存明文，正则匹配存表达式）
     */
    private String word;

    /**
     * 分类
     *
     * @see com.xspaceagi.system.spec.enums.SensitiveWordCategoryEnum
     */
    private String category;

    /**
     * 匹配方式：CONTAIN-包含、REGEX-正则
     *
     * @see com.xspaceagi.system.spec.enums.SensitiveWordMatchTypeEnum
     */
    private String matchType;

    /**
     * 触发策略：DISCONNECT-断开连接、REPLACE-替换
     *
     * @see com.xspaceagi.system.spec.enums.SensitiveWordActionEnum
     */
    private String action;

    /**
     * 替换字符（触发策略为REPLACE时生效，为空默认*）
     */
    private String replaceChar;

    /**
     * 状态：1-启用、0-禁用
     *
     * @see com.xspaceagi.system.spec.enums.StatusEnum
     */
    private Integer status;

    /**
     * 创建时间
     */
    private Date created;

    /**
     * 修改时间
     */
    private Date modified;
}
