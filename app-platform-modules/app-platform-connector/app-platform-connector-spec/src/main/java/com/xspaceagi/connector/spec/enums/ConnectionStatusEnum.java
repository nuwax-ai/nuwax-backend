package com.xspaceagi.connector.spec.enums;

/**
 * 用户连接的<strong>凭证健康度</strong>：由系统写入，表达「这条凭证现在还能不能用」。
 * <p>
 * 这里<strong>不表达用户意图</strong>。「用户要不要用它」是另一个正交的概念，落在
 * {@code connector_connection.enabled}（实体字段 {@code ConnectorConnection#enabled}），
 * 由用户通过 {@code POST /api/connector/connections/{id}/status} 控制。
 * 两者组合才决定一条连接能否被执行解析选中：{@code enabled = 1 AND status = 'active'}。
 * <p>
 * 之所以拆成两处：值域互斥但语义正交。合在一个字段里时，「我停用了一条已经过期的连接」无法表达，
 * 且 refresh 失败写 {@code expired}、refresh 成功写 {@code active} 都会顺带抹掉用户的启停决定。
 * 因此本枚举里<strong>不要再加表达用户意图的值</strong>。
 */
public enum ConnectionStatusEnum {

    /** 凭证正常可用 */
    ACTIVE("active"),

    /** 凭证过期（OAuth refresh 失败等），需要用户重新授权 */
    EXPIRED("expired"),

    /** 验活/执行出错 */
    ERROR("error"),

    /**
     * 历史兼容值。<strong>当前代码不再写入</strong>——断开一条连接实现为直接删除该行（连带删除凭证行），
     * 不存在「墓碑」行。保留此值仅为兼容 {@code status <> 'disconnected'} 这类防御性过滤条件。
     */
    DISCONNECTED("disconnected");

    private final String code;

    ConnectionStatusEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static ConnectionStatusEnum fromCode(String code) {
        for (ConnectionStatusEnum status : values()) {
            if (status.code.equalsIgnoreCase(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("unknown connection status: " + code);
    }
}
