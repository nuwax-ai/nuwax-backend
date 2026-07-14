package com.xspaceagi.file.web.controller.api;

import com.xspaceagi.agent.core.adapter.application.PublishApplicationService;
import com.xspaceagi.agent.core.adapter.dto.PublishedPermissionDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.file.application.service.FileManagementService;
import com.xspaceagi.file.domain.model.FileRecordDomain;
import com.xspaceagi.file.sdk.IFileAccessService;
import com.xspaceagi.file.web.dto.FileRecordVO;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static com.xspaceagi.file.web.controller.FileManagementController.toVO;

@Tag(name = "文件上传")
@Slf4j
@RestController
public class FileApiController {

    @Resource
    private IFileAccessService iFileAccessService;

    @Resource
    private FileManagementService fileManagementService;

    @Resource
    private PublishApplicationService publishApplicationService;
    ;

    @Operation(summary = "文件上传接口", description = "文件上传接口，返回文件网络地址")
    @PostMapping("/api/v1/file/upload")
    public ReqResult<FileRecordVO> uploadFile(@RequestParam("file") MultipartFile file,
                                              @RequestParam(value = "targetType", required = false, defaultValue = "Default") String targetType,
                                              @RequestParam(value = "targetId", required = false, defaultValue = "-1") Long targetId,
                                              @RequestParam(value = "metadata", required = false) String metadata) {

        if ("Agent".equals(targetType) && targetId > 0) {
            PublishedPermissionDto publishedPermissionDto = publishApplicationService.hasPermission(Published.TargetType.Agent, targetId);
            if (!publishedPermissionDto.isView()) {
                return ReqResult.error("No permission to upload file for this agent id");
            }
        }

        FileRecordDomain fileRecord = fileManagementService.uploadFile(file, RequestContext.get().getTenantId(),
                RequestContext.get().getUserId(), targetType, targetId, metadata, true);

        return ReqResult.success(toVO(fileRecord));
    }

    @GetMapping("/api/v1/file/ak")
    public ReqResult<String> fileAk(@RequestParam(name = "fileUrl") String fileUrl, @RequestParam(required = false) Integer download) {
        fileUrl = URLDecoder.decode(fileUrl, StandardCharsets.UTF_8);
        //fileUrl中移除ak参数
        fileUrl = fileUrl.replaceAll("([?&])ak(?:=[^&]*)?&?", "$1").replaceAll("\\?$", "");
        return ReqResult.success(iFileAccessService.getFileUrlWithAk(fileUrl, true, download != null ? download : 0));
    }
}
