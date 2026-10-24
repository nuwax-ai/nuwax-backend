package com.xspaceagi.agent.core.infra.rpc;

import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * 下游 HTTP 调用的 URL 编码支撑。
 * RestTemplate/WebClient 收到 String URL 时会把它当作未编码模板再编码一遍（% → %25），与
 * UriComponentsBuilder.toUriString() 的预编码叠加成双重编码：中文/空格参数到下游解码一次后仍是 %XX
 * 字面量（file-server readdir 报 ENOENT、git 定位报 Workspace does not exist 均此因）。
 * 因此 URL 含 query/路径参数时必须编码一次后以 URI 对象传入——两个客户端对 URI 不再改动。
 * 另注：Express simple query parser 会把 + 还原成空格，而 QUERY_PARAM 编码规则按 RFC 3986 不转义 +，故统一替换为 %2B。
 */
public final class UriEncodingSupport {

    private UriEncodingSupport() {
    }

    /** 只编码一次生成请求 URI；调用方须以 URI 对象传给 RestTemplate/WebClient，不要再传 String */
    public static URI buildEncodedUri(UriComponentsBuilder builder) {
        return URI.create(builder.build().encode().toUriString().replace("+", "%2B"));
    }
}
