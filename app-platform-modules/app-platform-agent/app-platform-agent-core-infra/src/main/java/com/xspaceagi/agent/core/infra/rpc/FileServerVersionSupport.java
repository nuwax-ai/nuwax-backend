package com.xspaceagi.agent.core.infra.rpc;

import com.xspaceagi.agent.core.infra.rpc.dto.SandboxServerConfig;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * file-server 版本探测与能力判断。
 */
@Slf4j
@Component
public class FileServerVersionSupport {

    /** 支持智能体级 skills/agents 实体存储 + 软链的最低版本 */
    public static final String AGENT_STORE_MIN_VERSION = "1.4.0";

    /** 版本探测缓存时间（毫秒） */
    private static final long VERSION_CACHE_TTL_MS = 60_000L;

    /** 版本探测超时（毫秒）：探测在请求调用链上（supportsAgentStore 等），file-server 挂起时必须快速失败，不能无限阻塞占用线程 */
    private static final int PROBE_CONNECT_TIMEOUT_MS = 2_000;
    private static final int PROBE_READ_TIMEOUT_MS = 2_000;

    private final RestTemplate probeRestTemplate = buildProbeRestTemplate();

    private static RestTemplate buildProbeRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(PROBE_CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(PROBE_READ_TIMEOUT_MS);
        return new RestTemplate(factory);
    }

    @Resource
    private SandboxServerConfigService sandboxServerConfigService;

    private final ConcurrentHashMap<String, CachedVersion> versionCache = new ConcurrentHashMap<>();

    /**
     * 是否支持 agentStore（实体存储 + 软链）。
     */
    public boolean supportsAgentStore(Long cId) {
        return isAtLeast(cId, AGENT_STORE_MIN_VERSION);
    }

    /**
     * file-server 版本是否 >= minVersion。
     * 探测失败或无 /api/version 时返回 false。
     */
    public boolean isAtLeast(Long cId, String minVersion) {
        String version = getVersion(cId);
        if (StringUtils.isBlank(version) || StringUtils.isBlank(minVersion)) {
            return false;
        }
        boolean ok = compareSemVer(version, minVersion) >= 0;
        log.info("[file-server-version] cId={} version={} minVersion={} matched={}",
                cId, version, minVersion, ok);
        return ok;
    }

    /**
     * 获取 file-server 的 package.json version；探测失败返回 null。
     */
    public String getVersion(Long cId) {
        SandboxServerConfig.SandboxServer sandboxServer = requireServer(cId);
        return getVersionByBaseUrl(sandboxServer.getServerFileUrl() + "/api", sandboxServer.getServerApiKey());
    }

    /**
     * 按 file-server 根地址探测版本（附带沙箱鉴权 key）；探测失败返回 null。
     */
    public String getVersionByFileUrl(String serverFileUrl, String apiKey) {
        if (StringUtils.isBlank(serverFileUrl)) {
            return null;
        }
        String base = serverFileUrl.endsWith("/")
                ? serverFileUrl.substring(0, serverFileUrl.length() - 1)
                : serverFileUrl;
        return getVersionByBaseUrl(base + "/api", apiKey);
    }

    /**
     * 当前会话绑定沙箱的 file-server 信息。
     */
    public SandboxServerConfig.SandboxServer resolveServer(Long cId) {
        return sandboxServerConfigService.selectServer(cId);
    }

    public String getVersionByBaseUrl(String baseUrl, String apiKey) {
        long now = System.currentTimeMillis();
        CachedVersion cached = versionCache.get(baseUrl);
        if (cached != null && now - cached.checkedAtMs < VERSION_CACHE_TTL_MS) {
            return cached.version;
        }

        String version = probeVersion(baseUrl, apiKey);
        versionCache.put(baseUrl, new CachedVersion(version, now));
        log.info("[file-server-version] probed version={}, baseUrl={}", version, baseUrl);
        return version;
    }

    private String probeVersion(String baseUrl, String apiKey) {
        String url = baseUrl + "/version";
        try {
            HttpHeaders headers = new HttpHeaders();
            WorkspaceServiceSupport.applySandboxAuthHeader(headers, apiKey);
            ResponseEntity<Map<String, Object>> entity = probeRestTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), new ParameterizedTypeReference<Map<String, Object>>() {
                    });
            Map<String, Object> body = entity.getBody();
            if (body == null || body.get("version") == null) {
                return null;
            }
            String version = body.get("version").toString().trim();
            return StringUtils.isBlank(version) ? null : version;
        } catch (Exception e) {
            log.info("[file-server-version] probe failed, treat as unknown. url={}, err={}",
                    url, e.getMessage());
            return null;
        }
    }

    private SandboxServerConfig.SandboxServer requireServer(Long cId) {
        SandboxServerConfig.SandboxServer sandboxServer;
        try {
            sandboxServer = sandboxServerConfigService.selectServer(cId);
        } catch (BizException e) {
            log.warn("[file-server-version] selectServer failed cId={}", cId, e);
            throw e;
        } catch (Exception e) {
            log.warn("[file-server-version] selectServer failed cId={}", cId, e);
            throw new BizException(e.getMessage());
        }
        if (sandboxServer == null || sandboxServer.getServerFileUrl() == null) {
            throw BizException.of(BizExceptionCodeEnum.agentSandboxNotFound);
        }
        return sandboxServer;
    }

    /**
     * 比较语义化版本号（仅比较数字段，忽略 -pre / +build 后缀）。
     *
     * @return 大于0 表示 v1大于v2；0 相等；小于0 表示 v1小于v2
     */
    public static int compareSemVer(String v1, String v2) {
        int[] p1 = parseSemVerParts(v1);
        int[] p2 = parseSemVerParts(v2);
        int len = Math.max(p1.length, p2.length);
        for (int i = 0; i < len; i++) {
            int a = i < p1.length ? p1[i] : 0;
            int b = i < p2.length ? p2[i] : 0;
            if (a != b) {
                return Integer.compare(a, b);
            }
        }
        return 0;
    }

    private static int[] parseSemVerParts(String version) {
        if (version == null) {
            return new int[]{0};
        }
        String core = version.trim();
        int cut = core.length();
        for (int i = 0; i < core.length(); i++) {
            char c = core.charAt(i);
            if (c == '-' || c == '+') {
                cut = i;
                break;
            }
        }
        core = core.substring(0, cut);
        String[] segs = core.split("\\.");
        int[] parts = new int[segs.length];
        for (int i = 0; i < segs.length; i++) {
            try {
                String seg = segs[i];
                int end = 0;
                while (end < seg.length() && Character.isDigit(seg.charAt(end))) {
                    end++;
                }
                parts[i] = end > 0 ? Integer.parseInt(seg.substring(0, end)) : 0;
            } catch (NumberFormatException e) {
                parts[i] = 0;
            }
        }
        return parts;
    }

    private static final class CachedVersion {
        /** null 表示探测失败 / 未知版本 */
        private final String version;
        private final long checkedAtMs;

        private CachedVersion(String version, long checkedAtMs) {
            this.version = version;
            this.checkedAtMs = checkedAtMs;
        }
    }
}
