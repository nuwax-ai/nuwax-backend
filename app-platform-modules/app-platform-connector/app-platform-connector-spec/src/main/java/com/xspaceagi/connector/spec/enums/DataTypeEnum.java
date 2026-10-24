package com.xspaceagi.connector.spec.enums;

/**
 * 参数数据类型。
 * <p>
 * 与平台插件/Agent 侧 DataTypeEnum 的同名子集保持结构对齐（枚举名一致，
 * JSON 序列化互相兼容）；连接器模块独立定义，不依赖 MCP/Agent 模块。
 */
public enum DataTypeEnum {
    String, // 文本
    Integer, // 整型数字
    Number, // 数字
    Boolean, // 布尔
    Object, // 对象
    Array_File, // 文件数组
    Array_String, // String 数组
    Array_Integer, // Integer 数组
    Array_Number, // Number 数组
    Array_Boolean, // Boolean 数组
    Array_Object, // Object 数组
}
