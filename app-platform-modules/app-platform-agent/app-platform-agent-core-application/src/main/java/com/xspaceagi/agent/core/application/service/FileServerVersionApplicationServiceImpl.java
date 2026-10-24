package com.xspaceagi.agent.core.application.service;

import com.xspaceagi.agent.core.adapter.application.FileServerVersionApplicationService;
import com.xspaceagi.agent.core.adapter.dto.FileServerVersionDto;
import com.xspaceagi.agent.core.infra.rpc.FileServerVersionSupport;
import com.xspaceagi.agent.core.infra.rpc.SandboxServerConfigService;
import com.xspaceagi.agent.core.infra.rpc.dto.SandboxServerConfig;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class FileServerVersionApplicationServiceImpl implements FileServerVersionApplicationService {

    @Resource
    private FileServerVersionSupport fileServerVersionSupport;
    @Resource
    private SandboxServerConfigService sandboxServerConfigService;

    @Override
    public List<FileServerVersionDto> listCurrentTenantVersions() {
        TenantConfigDto tenantConfig = (TenantConfigDto) RequestContext.get().getTenantConfig();
        if (tenantConfig == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail, "tenantConfig is null");
        }
        List<SandboxServerConfig.SandboxServer> servers = sandboxServerConfigService.listGlobalServers(tenantConfig);
        List<FileServerVersionDto> result = new ArrayList<>();
        for (SandboxServerConfig.SandboxServer server : servers) {
            result.add(toDto(server));
        }
        return result;
    }

    @Override
    public FileServerVersionDto getByConversation(Long cId) {
        if (cId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "cId");
        }
        SandboxServerConfig.SandboxServer server = fileServerVersionSupport.resolveServer(cId);
        return toDto(server);
    }

    private FileServerVersionDto toDto(SandboxServerConfig.SandboxServer server) {
        if (server == null) {
            return FileServerVersionDto.builder().build();
        }
        String fileUrl = server.getServerFileUrl();
        String version = StringUtils.isNotBlank(fileUrl)
                ? fileServerVersionSupport.getVersionByFileUrl(fileUrl, server.getServerApiKey())
                : null;
        return FileServerVersionDto.builder()
                .serverId(server.getServerId())
                .serverName(server.getServerName())
                .serverFileUrl(fileUrl)
                .version(version)
                .build();
    }
}
