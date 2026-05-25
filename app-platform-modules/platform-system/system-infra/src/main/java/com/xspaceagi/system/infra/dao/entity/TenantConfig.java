package com.xspaceagi.system.infra.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Builder
@AllArgsConstructor
@NoArgsConstructor
@Data
@TableName(value = "tenant_config", autoResultMap = true)
public class TenantConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField(value = "_tenant_id")
    private Long tenantId;

    private String name;

    private Object value;

    private String description;

    private ConfigCategory category;

    private InputType inputType;

    private DataType dataType;

    private String notice;

    private String placeholder;

    private Integer minHeight;

    private boolean required;

    private Integer sort;

    public enum ConfigCategory {
        BaseConfig, ModelSetting, AgentSetting, DomainBind, TemplateConfig, Payment, Subscription, Credit
    }

    public enum DataType {
        String, Number, Array
    }

    public enum InputType {
        Input, MultiInput, Select, MultiSelect, Textarea, File
    }
}