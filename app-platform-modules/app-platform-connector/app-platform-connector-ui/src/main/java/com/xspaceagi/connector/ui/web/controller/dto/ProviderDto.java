package com.xspaceagi.connector.ui.web.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Date;
import java.util.List;

@Data
@Schema(description = "连接器 provider 视图")
public class ProviderDto {

    private Long id;

    private Long spaceId;

    private String service;

    private String displayName;

    /** 连接器介绍 */
    private String description;

    private String icon;

    private String category;

    private List<String> tags;

    private String authType;

    private String baseUrl;

    /** key 注入位置/OAuth 端点/scopes */
    private Object authConfig;

    /** OAuth App 模式：platform/byo */
    private String oauthAppMode;

    private String source;

    private String providerVersion;

    private String managedBy;

    private Boolean proxyEnabled;

    /** 连接器目录条目自身的启停：enabled/disabled（与 connected / connectionEnabled 无关） */
    private String status;

    /** 官方目录排序权重，越小越靠前 */
    private Integer sortOrder;

    /** 当前用户是否已建连接（连接行存在即算，停用仍为 true） */
    private Boolean connected;

    /** 当前用户是否已启用该连接器的任一连接（连接行 enabled=1）；未连接时恒为 false */
    private Boolean connectionEnabled;

    /**
     * 当前用户在该连接器下的连接 id；未连接时为 {@code null}。
     * <p>
     * 连接级接口（启停 / 断开 / 验活）要的都是<b>连接 id</b>，不是连接器 id——卡片上直接拿
     * {@link #id}（连接器 id）去调 {@code /connections/{id}/status} 必然报「连接不存在」。
     * 这里把连接 id 随 provider 一起发出去，卡片就不必自己去猜或再打一次 {@code /connections}。
     * <p>
     * 一个 provider 可以挂多条连接（同一 service 建多条，如飞书的应用身份 + 用户身份），
     * 此时这里给的是<b>执行时会用到的那一条</b>（{@code enabled=1 且 status=active} 中 id 最小者，
     * 与 {@code ConnectorConnectionRepository#firstExecutable} 同一条），全量见 {@link #connections}。
     */
    private Long connectionId;

    /**
     * 当前用户在该连接器下的全部连接（仅 profile：id/name/身份/开关/凭证健康度，绝不含凭证）。
     * <p>
     * 未连接时为 {@code null}（不给空数组，免得全量列表里 1000+ 条未连接记录每行都驮一个 {@code []}）。
     * 只有一条时它和 {@link #connectionId} 指向同一条；多条时用它做「停用哪一条」的选择。
     */
    private List<ConnectionBrief> connections;

    /** 动作数 */
    private Integer actionCount;

    private Date modified;

    private Date created;

    /**
     * provider 视图里内嵌的连接摘要（{@code ConnectionDto} 的裁剪版：去掉 profile/grants 等只对
     * 连接详情页有意义的重字段，避免目录列表被撑大）。字段语义与 {@code ConnectionDto} 一一对应。
     */
    @Data
    @Schema(description = "provider 下的连接摘要（仅 profile，无凭证）")
    public static class ConnectionBrief {

        private Long id;

        private Long spaceId;

        private String name;

        /** 凭证健康度：active/expired/error。由系统写，不表达用户意图 */
        private String status;

        /** 用户启用开关；与 status 正交，两者同时成立才可被执行解析选中 */
        private Boolean enabled;

        /** 令牌身份：tenant=应用身份、user=用户身份；存量连接读不到时为 null（未知） */
        private String identity;
    }
}
