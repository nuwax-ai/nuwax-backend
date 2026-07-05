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

@TableName(value = "resource_group")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ResourceGroup {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField(value = "_tenant_id")
    private Long tenantId;

    private Long spaceId;

    private Long creatorId;

    private String name;

    private String description;

    private String icon;

    private Integer toolCount;

    private String type;

    private Date created;

    private Date modified;
}
