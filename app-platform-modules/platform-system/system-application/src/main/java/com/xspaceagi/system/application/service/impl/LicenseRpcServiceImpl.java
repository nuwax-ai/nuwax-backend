package com.xspaceagi.system.application.service.impl;

import com.xspaceagi.system.sdk.service.ILicenseRpcService;
import com.xspaceagi.system.spec.license.LicenseResult;
import com.xspaceagi.system.spec.license.LicenseVerifier;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 授权码读取实现：验签结果按授权串缓存，运行期只读缓存。
 *
 * <p>缓存带有效期（{@link #TTL_MILLIS}）：换授权串、跨过到期日都能在下一次刷新时自愈，不必重启进程；
 * 缓存期内的请求不再验签、不再探测设备指纹。
 *
 * <p>★缓存按授权串隔离，不是全局单值 —— 平台是多租户的，每个请求传进来的是<b>当前租户</b>的授权串；
 * 共用一个值的话，先到的租户会把结论（含产品版本）借给其它租户。
 */
@Slf4j
@Service
public class LicenseRpcServiceImpl implements ILicenseRpcService {

    /**
     * 覆盖内置验签公钥（Base64 SPKI）；留空用内置那把
     */
    @Value("${license-verify.public-key:}")
    private String publicKey;

    /**
     * 缓存有效期：到期后重新验签
     */
    private static final long TTL_MILLIS = 10 * 60 * 1000L;

    /**
     * 缓存条数上限；超出先丢弃过期的，仍超则整体丢弃（代价只是重新验签）
     */
    private static final int MAX_ENTRIES = 64;

    /**
     * key = 授权串的指纹而非原串：既让「同一串」天然共享结论，也不给明文凭据在内存里多留一份。
     */
    private final Map<String, Checked> cache = new ConcurrentHashMap<>();

    private record Checked(LicenseResult result, long checkedAt) {
    }

    @Override
    public LicenseResult getResult(String licenseContent) {
        String key = cacheKey(licenseContent);
        Checked cached = cache.get(key);
        if (cached != null && System.currentTimeMillis() - cached.checkedAt() <= TTL_MILLIS) {
            return cached.result();
        }
        // 设备指纹由 LicenseVerifier 内部按本机实际探测（machine-id / 主板 UUID / 网卡 MAC），
        // 刻意不做配置项覆盖 —— 能改配置就等于能改指纹，那这份绑定只是摆设。
        // 并发下可能有两个线程重复验签同一串：重复一次无副作用，不为它加锁。
        LicenseResult verified = LicenseVerifier.verify(licenseContent, publicKey);
        report(verified);
        cache.put(key, new Checked(verified, System.currentTimeMillis()));
        evictIfNeeded();
        return verified;
    }

    /**
     * 空白（未配置授权）归到同一档 —— 都是「社区版形态」
     */
    private static String cacheKey(String licenseContent) {
        if (StringUtils.isBlank(licenseContent)) {
            return "absent";
        }
        String content = licenseContent.trim();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(hash, 16));
        } catch (Exception e) {
            // SHA-256 是 JDK 必备算法，正常到不了这里；真到了也不能拿错结论，退回按串本身区分
            return "raw:" + content;
        }
    }

    private void evictIfNeeded() {
        if (cache.size() <= MAX_ENTRIES) {
            return;
        }
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(entry -> now - entry.getValue().checkedAt() > TTL_MILLIS);
        if (cache.size() > MAX_ENTRIES) {
            cache.clear();
        }
    }

    private void report(LicenseResult verified) {
        switch (verified.getStatus()) {
            case ABSENT -> log.info("未配置授权码（license），按社区版形态运行");
            case VALID -> log.info("授权码校验通过：{}", describe(verified));
            case GRACE -> log.warn("授权码已到期，处于宽限期，请尽快续期：{}", describe(verified));
            case EXPIRED -> log.error("授权码已过期：{}", describe(verified));
            case INVALID -> log.error("授权码无效：{}", verified.getReason());
            // 设备绑定不符：原因里带了本机候选指纹，客户拿它来找签发方换发即可
            case MISMATCH -> log.error("授权码设备绑定不符：{}", verified.getReason());
        }
    }

    private String describe(LicenseResult verified) {
        if (verified.getPayload() == null) {
            return verified.getReason();
        }
        String bind = verified.getPayload().getBind() == null
                || verified.getPayload().getBind().getDevices() == null
                || verified.getPayload().getBind().getDevices().isEmpty() ? "未绑定设备" : "已绑定设备";
        return String.format("客户=%s，授权号=%s，版本=%s，%s，%s",
                verified.getPayload().getCustomer(),
                verified.getPayload().getLicId(),
                verified.getPayload().getEdition(),
                bind,
                verified.getReason());
    }
}
