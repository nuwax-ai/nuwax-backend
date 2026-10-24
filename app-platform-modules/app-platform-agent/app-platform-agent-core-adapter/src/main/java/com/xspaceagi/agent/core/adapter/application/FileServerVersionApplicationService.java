package com.xspaceagi.agent.core.adapter.application;

import com.xspaceagi.agent.core.adapter.dto.FileServerVersionDto;

import java.util.List;

/**
 * file-server 版本探测（排查用）
 */
public interface FileServerVersionApplicationService {

    /**
     * 探测当前租户全局沙箱上的 file-server 版本
     */
    List<FileServerVersionDto> listCurrentTenantVersions();

    /**
     * 按会话探测其绑定沙箱上的 file-server 版本
     */
    FileServerVersionDto getByConversation(Long cId);
}
