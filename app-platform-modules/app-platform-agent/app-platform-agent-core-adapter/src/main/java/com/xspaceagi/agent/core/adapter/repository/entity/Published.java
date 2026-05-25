package com.xspaceagi.agent.core.adapter.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xspaceagi.system.spec.common.JsonTypeHandlerWithoutType;
import com.xspaceagi.system.spec.utils.I18nUtil;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@TableName("published")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Published {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("_tenant_id")
    private Long tenantId;

    private Long spaceId;

    @TableField("user_id")
    private Long userId;

    @TableField("target_type")
    private TargetType targetType;

    private TargetSubType targetSubType;

    @TableField("target_id")
    private Long targetId;

    private String name;

    private String description;

    private String icon;

    private String config;

    @TableField(value = "ext", typeHandler = JsonTypeHandlerWithoutType.class)
    private Object ext;

    private String remark;

    private PublishChannel channel;

    private PublishScope scope;

    private String category;

    private Integer allowCopy;

    private Integer onlyTemplate;

    private Integer accessControl;

    private Date modified;

    private Date created;

    public enum PublishStatus {
        Developing,
        Applying,
        Published,
        Rejected
    }

    //发布渠道:Square 广场;Space空间
    public enum PublishChannel {
        Square,
        System,
        Space
    }

    //Tenant', 'Global'
    public enum PublishScope {
        //空间
        Space,
        Tenant,
        Global
    }

    public enum TargetType {
        Agent,
        Plugin,
        Workflow,
        Knowledge,
        Table,
        Skill,
        Model
    }

    public enum TargetSubType {
        Multi,
        Single,
        WorkflowChat,
        ChatBot,
        TaskAgent,
        Agent,
        PageApp
    }

    // Return name based on targetType
    public static String getTargetTypeName(TargetType targetType) {
        return switch (targetType) {
            case Agent -> I18nUtil.systemMessage("Backend.Published.TargetType.Agent");
            case Plugin -> I18nUtil.systemMessage("Backend.Published.TargetType.Plugin");
            case Workflow -> I18nUtil.systemMessage("Backend.Published.TargetType.Workflow");
            case Knowledge -> I18nUtil.systemMessage("Backend.Published.TargetType.Knowledge");
            case Table -> I18nUtil.systemMessage("Backend.Published.TargetType.Table");
            case Skill -> I18nUtil.systemMessage("Backend.Published.TargetType.Skill");
            default -> "Unknown";
        };
    }
}
