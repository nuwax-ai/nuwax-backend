package com.xspaceagi.agent.core.adapter.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * file-server 版本探测结果（排查用）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FileServerVersionDto implements Serializable {

    private String serverId;
    private String serverName;
    /** file-server 根地址 */
    private String serverFileUrl;
    /** package.json version；探测失败为 null */
    private String version;
}
