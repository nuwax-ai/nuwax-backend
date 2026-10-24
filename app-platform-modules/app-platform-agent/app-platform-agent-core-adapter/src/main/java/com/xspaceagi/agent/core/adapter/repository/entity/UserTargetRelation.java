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

@TableName("user_target_relation")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UserTargetRelation {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id; // 关系ID

    @TableField(value = "_tenant_id")
    private Long tenantId; // 商户ID

    private Long userId; // 用户ID

    private Published.TargetType targetType;

    private Long targetId; // 目标对象ID

    private OpType type; // 关系类型，可选值：Edit, Add, Like, Collect, DevCollect, Conversation, Enable

    private String targetSubType; // 目标子类型：ChatBot/TaskAgent 智能体，PageApp 网页应用

    private String extra;

    private Date modified; // 更新时间

    private Date created; // 创建时间

    public enum OpType {
        Edit, Add, Like, Collect, DevCollect, Conversation, Enable
    }
}
