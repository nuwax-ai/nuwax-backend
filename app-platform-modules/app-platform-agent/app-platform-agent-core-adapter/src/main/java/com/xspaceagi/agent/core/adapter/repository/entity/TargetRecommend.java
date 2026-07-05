package com.xspaceagi.agent.core.adapter.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@TableName("target_recommend")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TargetRecommend {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("_tenant_id")
    private Long tenantId;

    @TableField("target_type")
    private TargetType targetType;

    @TableField("target_id")
    private Long targetId;

    @TableField("rec_type")
    private RecType recType;

    @TableField("function_type")
    private FunctionType functionType;

    private String label;

    private String icon;

    private String placeholder;

    private Integer sort;

    private Date modified;

    private Date created;

    public enum TargetType {
        Agent,
        PageApp,
        Skill,
        Plugin,
        Workflow
    }

    public enum RecType {
        Home,
        Official,
        ChatBoxNav
    }

    public enum FunctionType {
        AgentDev,
        PageAppDev,
        SkillDev,
        PluginDev,
        Chat
    }
}
