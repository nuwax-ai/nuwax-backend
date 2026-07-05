package com.xspaceagi.agent.core.adapter.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.xspaceagi.agent.core.spec.enums.CodeLanguageEnum;
import com.xspaceagi.agent.core.spec.enums.PluginTypeEnum;
import lombok.Data;

import java.util.Date;

@Data
public class PluginConfig {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField(value = "_tenant_id")
    private Long tenantId;

    private Long spaceId;

    private Long creatorId;

    private String name;

    private String description;

    private String icon;

    private PluginTypeEnum type;

    private CodeLanguageEnum codeLang;

    private Published.PublishStatus publishStatus;

    private String config;

    private Date modified;

    private Date created;

    private Long devAgentConversationId;
}
