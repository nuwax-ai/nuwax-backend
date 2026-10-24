package com.xspaceagi.connector.spec.enums;

/**
 * 连接器目录来源（设计文档 §4.1 三个来源）。
 */
public enum ConnectorSourceEnum {

    /** open-connector catalog 导入 */
    OPEN_CONNECTOR("open_connector"),

    /** 女娲平台自建/手工创建 */
    NUWAX("nuwax"),

    /** 生态市场 */
    MARKET("market");

    private final String code;

    ConnectorSourceEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
