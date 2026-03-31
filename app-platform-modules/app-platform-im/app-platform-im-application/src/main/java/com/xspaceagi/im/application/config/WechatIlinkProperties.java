package com.xspaceagi.im.application.config;

import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 微信 iLink 模块配置（对齐 openclaw-weixin 1.0.3 临时目录与可观测项）。
 */
@Data
@ConfigurationProperties(prefix = "wechat.ilink")
public class WechatIlinkProperties {

    /**
     * 媒体/下载等使用的根目录；未配置时使用 {@code java.io.tmpdir} 下子目录。
     */
    private String workDir;

    /**
     * 解析并创建可写目录：{@code workDir/wechat-ilink} 或 {@code java.io.tmpdir/wechat-ilink}。
     */
    public Path resolveWorkDirectory() throws IOException {
        String base = StringUtils.isNotBlank(workDir) ? workDir.trim() : System.getProperty("java.io.tmpdir");
        Path p = Paths.get(base, "wechat-ilink");
        Files.createDirectories(p);
        return p;
    }
}
