package com.xspaceagi.agent.core.application.api;

import com.xspaceagi.agent.core.adapter.application.IComputerFileApplicationService;
import com.xspaceagi.agent.core.adapter.dto.ComputerFileInfo;
import com.xspaceagi.agent.core.sdk.ISandboxFileRpcService;
import com.xspaceagi.agent.core.sdk.dto.SandboxFileGenerateRequest;
import com.xspaceagi.agent.core.sdk.dto.SandboxFileInfoDto;
import com.xspaceagi.agent.core.sdk.dto.SandboxFileListRequest;
import com.xspaceagi.agent.core.sdk.dto.SandboxFileUploadRequest;
import com.xspaceagi.agent.core.sdk.dto.SandboxFilesUpdateRequest;
import com.xspaceagi.agent.core.sdk.dto.SandboxFilesUploadRequest;
import com.xspaceagi.agent.core.sdk.dto.SandboxImportProjectRequest;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SandboxFileRpcServiceImpl implements ISandboxFileRpcService {

    @Resource
    private IComputerFileApplicationService computerFileApplicationService;

    @Override
    public Map<String, Object> getFileList(SandboxFileListRequest request) {
        return computerFileApplicationService.getFileList(
                request.getConversationUserId(),
                request.getCId(),
                request.getProxyPath(),
                null,
                request.getCustomTargetDir(),
                request.getRelativePath(),
                request.getRecursive(),
                null,
                request.getType(),
                request.getLimit()
        );
    }

    @Override
    public Map<String, Object> filesUpdate(SandboxFilesUpdateRequest request) {
        return computerFileApplicationService.filesUpdate(
                request.getConversationUserId(),
                request.getCId(),
                toComputerFileInfoList(request.getFiles()),
                null,
                request.getCustomTargetDir()
        );
    }

    @Override
    public Map<String, Object> generateFile(SandboxFileGenerateRequest request) {
        return computerFileApplicationService.generateFile(
                request.getConversationUserId(),
                request.getCId(),
                request.getFileName(),
                request.getContent(),
                null,
                request.getCustomTargetDir()
        );
    }

    @Override
    public Map<String, Object> uploadFiles(SandboxFilesUploadRequest request) {
        return computerFileApplicationService.uploadFiles(
                request.getConversationUserId(),
                request.getCId(),
                request.getFilePaths(),
                request.getFiles(),
                null,
                request.getCustomTargetDir()
        );
    }

    @Override
    public Map<String, Object> importProject(SandboxImportProjectRequest request) {
        return computerFileApplicationService.importProject(
                request.getConversationUserId(),
                request.getCId(),
                request.getFile(),
                null,
                request.getCustomTargetDir()
        );
    }

    private List<ComputerFileInfo> toComputerFileInfoList(List<SandboxFileInfoDto> files) {
        if (CollectionUtils.isEmpty(files)) {
            return Collections.emptyList();
        }
        return files.stream().map(this::toComputerFileInfo).collect(Collectors.toList());
    }

    private ComputerFileInfo toComputerFileInfo(SandboxFileInfoDto dto) {
        ComputerFileInfo info = new ComputerFileInfo();
        info.setName(dto.getName());
        info.setBinary(dto.isBinary());
        info.setSizeExceeded(dto.isSizeExceeded());
        info.setContents(dto.getContents());
        info.setRenameFrom(dto.getRenameFrom());
        info.setOperation(dto.getOperation());
        info.setIsDir(dto.getIsDir());
        return info;
    }
}
