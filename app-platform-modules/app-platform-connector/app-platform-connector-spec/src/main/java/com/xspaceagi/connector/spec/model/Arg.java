package com.xspaceagi.connector.spec.model;

import com.xspaceagi.connector.spec.enums.DataTypeEnum;

import java.io.Serializable;
import java.util.List;

/**
 * 参数定义（Arg 树）。
 * <p>
 * 结构与平台插件侧 Arg 的核心字段对齐：name/description/dataType/require/enable/subArgs，
 * 枚举名一致、JSON 互相兼容；连接器模块独立定义，不依赖 MCP/Agent 模块。
 * <p>
 * 连接器的动作入参（connector_action.input_args JSON）即为本结构的数组：
 * {@code [{"name":"...","description":"...","dataType":"String","require":true,"subArgs":[...]}]}。
 */
public class Arg implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 参数名称，符合函数命名规则 */
    private String name;

    /** 参数详细描述信息 */
    private String description;

    /** 数据类型 */
    private DataTypeEnum dataType;

    /** 是否必须 */
    private boolean require;

    /** 是否为开启，如果不开启，插件使用者和大模型均看不见该参数；null 视为开启 */
    private Boolean enable;

    /** 下级参数 */
    private List<Arg> subArgs;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public DataTypeEnum getDataType() {
        return dataType;
    }

    public void setDataType(DataTypeEnum dataType) {
        this.dataType = dataType;
    }

    public boolean isRequire() {
        return require;
    }

    public void setRequire(boolean require) {
        this.require = require;
    }

    public Boolean getEnable() {
        return enable;
    }

    public void setEnable(Boolean enable) {
        this.enable = enable;
    }

    public List<Arg> getSubArgs() {
        return subArgs;
    }

    public void setSubArgs(List<Arg> subArgs) {
        this.subArgs = subArgs;
    }
}
