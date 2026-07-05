package com.xspaceagi.agent.core.adapter.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xspaceagi.agent.core.adapter.dto.SkillFileDto;
import com.xspaceagi.agent.core.adapter.typehandler.SkillFileListTypeHandler;
import com.xspaceagi.system.spec.common.JsonTypeHandlerWithoutType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

@TableName(value = "skill_config", autoResultMap = true)
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SkillConfig {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id; // 主键ID

    private String name; // 技能名称

    private String description; // 技能描述

    private String icon; // 技能图标

    @TableField(value = "files", typeHandler = SkillFileListTypeHandler.class)
    private List<SkillFileDto> files; // 文件内容

    @TableField(value = "ext", typeHandler = JsonTypeHandlerWithoutType.class)
    private Object ext; // 扩展字段

    private Published.PublishStatus publishStatus; // 发布状态

    @TableField(value = "_tenant_id")
    private Long tenantId; // 租户ID

    private Long spaceId; // 空间ID

    private Date created; // 创建时间

    private Long creatorId; // 创建人ID

    private String creatorName; // 创建人

    private Date modified; // 更新时间

    private Long modifiedId; // 最后修改人ID

    private String modifiedName; // 最后修改人

    private Integer yn; // 逻辑标记,1:有效;-1:无效

    private Long devAgentConversationId;
}