package com.xspaceagi.system.spec.license;

import org.apache.commons.lang3.StringUtils;

import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 本机设备指纹：授权码里 {@code bind.devices} 比对用的「这台机器是谁」。
 *
 * <p>形态 {@code 来源:哈希}，如 {@code machine-id:qQ3n…}、{@code mac-eth0:7Zk…}。原始标识（machine-id、
 * 主板 UUID、MAC）经 SHA-256 取前 16 字节再 base64url —— 授权串会被客户转发、截图、贴进工单，
 * 不该把机器原始标识明文带出去。
 *
 * <p><b>为什么是「一组候选」而不是「一个指纹」</b>：任何单一标识都可能变（换网卡、容器重建、
 * 系统重装），把多来源合成一个哈希会让「任一来源变化 ⇒ 授权全废」。这里改成列出本机所有可用的
 * 候选，授权码里存**允许的集合**，本机命中任意一个即通过 —— 换网卡不会失效，拷贝到别的机器仍然拦得住。
 *
 * <p><b>刻意不提供配置项覆盖</b>：能改配置就等于能改指纹，那这份绑定只是摆设（历史 AES 授权码的
 * 教训 —— 密钥硬编码，等于公开）。探测不到就如实失败，不做「取不到就不校验」的兜底。
 *
 * <p>⚠️ 与 {@code tools/license-issuer/IssueLicense.java} 的 {@code fingerprint} 子命令是**同一套算法**
 * （该工具刻意零依赖、不进平台 classpath，故为源码级重复）；改动任一侧必须同步另一侧，否则签出来的
 * 绑定值平台永远匹配不上。
 */
public final class DeviceFingerprint {

    /** 哈希截断长度：16 字节（128 位）足够，且比整串短一半，便于放进授权串与工单 */
    private static final int HASH_BYTES = 16;

    /** Linux 机器标识（首选来源：主机重装才变，容器里通常也有） */
    private static final String[] MACHINE_ID_FILES = {"/etc/machine-id", "/var/lib/dbus/machine-id"};

    /** 主板 UUID（次选：整机唯一，容器里读的是宿主机的） */
    private static final String DMI_UUID_FILE = "/sys/class/dmi/id/product_uuid";

    /**
     * 探测本机候选指纹（顺序稳定：machine-id → 主板 UUID → 各网卡 MAC 按网卡名排序）。
     *
     * @return 可能为空 —— 探测不到任何标识时返回空列表（调用方按「不在绑定范围内」处理，不静默放行）
     */
    public static List<String> detect() {
        Set<String> fingerprints = new LinkedHashSet<>();

        for (String file : MACHINE_ID_FILES) {
            String value = readFirstLine(file);
            if (StringUtils.isNotBlank(value)) {
                fingerprints.add(of("machine-id", value));
                break;
            }
        }

        String dmiUuid = readFirstLine(DMI_UUID_FILE);
        if (StringUtils.isNotBlank(dmiUuid)) {
            fingerprints.add(of("dmi-uuid", dmiUuid));
        }

        for (Source source : macSources()) {
            fingerprints.add(of(source.label(), source.raw()));
        }

        return new ArrayList<>(fingerprints);
    }

    /** 一个可参与指纹计算的来源：{@code label} 是进授权串的短标签（不含原始标识），{@code raw} 是原始值 */
    private record Source(String label, String raw) {
    }

    /**
     * 单个来源的指纹：{@code 来源 + ":" + base64url(sha256(来源|原始值) 前 16 字节)}。
     *
     * <p>把来源名一并吃进哈希，避免不同来源恰好同值时互相冒充。
     */
    public static String of(String source, String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] full = digest.digest((source + "|" + raw).getBytes(StandardCharsets.UTF_8));
            byte[] head = new byte[HASH_BYTES];
            System.arraycopy(full, 0, head, 0, HASH_BYTES);
            return source + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(head);
        } catch (Exception e) {
            throw new IllegalStateException("计算设备指纹失败（SHA-256 不可用）", e);
        }
    }

    // ------------------------------------------------------------------ 探测

    /** 本机所有物理网卡候选，按网卡名排序；跳过回环 / 虚拟 / 无硬件地址 / 全零 MAC */
    private static List<Source> macSources() {
        List<Source> macs = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return macs;
            }
            for (NetworkInterface nic : Collections.list(interfaces)) {
                if (nic.isLoopback() || nic.isVirtual()) {
                    continue;
                }
                byte[] hardware = nic.getHardwareAddress();
                if (hardware == null || hardware.length != 6 || isAllZero(hardware)) {
                    continue;
                }
                StringBuilder hex = new StringBuilder();
                for (byte b : hardware) {
                    hex.append(String.format("%02X", b));
                }
                // 原始值用「网卡名:MAC」：同一台机器上两张网卡不会混，且标签只带网卡名、不带 MAC
                macs.add(new Source("mac-" + nic.getName(), nic.getName() + ":" + hex));
            }
        } catch (Exception e) {
            // 取不到网卡（权限 / 平台限制）时不抛：machine-id、主板 UUID 可能仍然可用
            return macs;
        }
        macs.sort(Comparator.comparing(Source::label));
        return macs;
    }

    private static boolean isAllZero(byte[] bytes) {
        for (byte b : bytes) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static String readFirstLine(String file) {
        try {
            Path path = Path.of(file);
            if (!Files.isReadable(path)) {
                return null;
            }
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (StringUtils.isNotBlank(line)) {
                    return line.trim();
                }
            }
        } catch (Exception e) {
            // 文件不存在 / 无权限 / 内容异常：当作这一来源不可用，换下一个
        }
        return null;
    }

    private DeviceFingerprint() {
    }
}
