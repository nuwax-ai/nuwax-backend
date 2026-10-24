package com.xspaceagi.system.spec.enums;

import java.util.Locale;

/**
 * 产品版本（同一套代码交付的两个形态）。
 *
 * <p>具体是哪一个，**以授权码里声明的 {@code edition} 为准**（验签通过才算数，客户改不了）；
 * 授权码没声明时才回落到启动配置 {@code app.edition}。见 {@code IEditionRpcService}。
 *
 * <p>本枚举只负责标识「当前跑的是哪个版本」，<b>不管控任何功能</b> —— 哪些能力按版本放行由使用方
 * 拿到本值后自行判断。
 *
 * <p>与租户级的 {@code TenantConfigDto.isCommercialEdition}（商业版/社区版）不是一回事：
 * 那个是租户维度的授权标记，本枚举是部署维度的产品形态。
 */
public enum EditionEnum {

    /**
     * AIOS —— 女娲智能体 OS，仓库主线形态
     */
    AIOS("aios", "AIOS"),

    /**
     * AI 服务平台
     */
    AISP("aisp", "AI 服务平台");

    private final String code;

    private final String name;

    EditionEnum(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    /**
     * 解析配置值。大小写不敏感，忽略 {@code -/_/空格}，并兼容少量别名；
     * 值为空返回 null，认不出也返回 null —— 由调用方决定是兜底还是报错。
     */
    public static EditionEnum parse(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").replace(" ", "");
        if (value.isEmpty()) {
            return null;
        }
        switch (value) {
            case "aios":
                return AIOS;
            case "aisp":
            case "aiserviceplatform":
            case "aiservice":
            case "aiplatform":
                return AISP;
            default:
                return null;
        }
    }

    /**
     * 合法取值清单，用于错误提示
     */
    public static String codes() {
        StringBuilder builder = new StringBuilder();
        for (EditionEnum edition : values()) {
            if (builder.length() > 0) {
                builder.append(" / ");
            }
            builder.append(edition.code);
        }
        return builder.toString();
    }
}
