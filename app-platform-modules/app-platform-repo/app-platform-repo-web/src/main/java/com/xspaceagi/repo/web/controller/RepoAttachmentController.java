package com.xspaceagi.repo.web.controller;

import com.xspaceagi.repo.application.service.RepoAttachmentApplicationService;
import com.xspaceagi.repo.domain.model.RepoAttachmentDomain;
import com.xspaceagi.repo.web.dto.RepoAttachmentCreateRequest;
import com.xspaceagi.repo.web.dto.RepoAttachmentVO;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 文档附件控制器（映射挂接/列表/软删，文件本体在 file 模块）。
 */
@Slf4j
@RestController
@RequestMapping("/api/repo")
@RequiredArgsConstructor
@Tag(name = "Repo Attachment", description = "文档协作：附件")
public class RepoAttachmentController {

    private final RepoAttachmentApplicationService repoAttachmentApplicationService;

    @PostMapping("/pages/{pageId}/attachments")
    @Operation(summary = "挂接页面级附件")
    public ReqResult<RepoAttachmentVO> addPageAttachment(@PathVariable Long pageId,
                                                         @RequestBody RepoAttachmentCreateRequest request) {
        RepoAttachmentDomain attachment = repoAttachmentApplicationService.addPageAttachment(
                pageId, request.getFileKey(), request.getFileName(), request.getMime(), request.getSize());
        return ReqResult.success(toVO(attachment));
    }

    @PostMapping("/spaces/{spaceId}/attachments")
    @Operation(summary = "挂接空间级附件")
    public ReqResult<RepoAttachmentVO> addSpaceAttachment(@PathVariable Long spaceId,
                                                          @RequestBody RepoAttachmentCreateRequest request) {
        RepoAttachmentDomain attachment = repoAttachmentApplicationService.addSpaceAttachment(
                spaceId, request.getFileKey(), request.getFileName(), request.getMime(), request.getSize());
        return ReqResult.success(toVO(attachment));
    }

    @GetMapping("/pages/{pageId}/attachments")
    @Operation(summary = "查询页面附件列表")
    public ReqResult<List<RepoAttachmentVO>> listPageAttachments(@PathVariable Long pageId) {
        return ReqResult.success(repoAttachmentApplicationService.listPageAttachments(pageId).stream()
                .map(RepoAttachmentController::toVO)
                .collect(Collectors.toList()));
    }

    @GetMapping("/spaces/{spaceId}/attachments")
    @Operation(summary = "查询空间全部附件（含页面级与空间级）")
    public ReqResult<List<RepoAttachmentVO>> listSpaceAttachments(@PathVariable Long spaceId) {
        return ReqResult.success(repoAttachmentApplicationService.listSpaceAttachments(spaceId).stream()
                .map(RepoAttachmentController::toVO)
                .collect(Collectors.toList()));
    }

    @DeleteMapping("/attachments/{attachmentId}")
    @Operation(summary = "软删除附件（限上传人或空间管理员/属主）")
    public ReqResult<Void> deleteAttachment(@PathVariable Long attachmentId) {
        repoAttachmentApplicationService.deleteAttachment(attachmentId);
        return ReqResult.success();
    }

    private static RepoAttachmentVO toVO(RepoAttachmentDomain domain) {
        return RepoAttachmentVO.builder()
                .id(domain.getId())
                .spaceId(domain.getSpaceId())
                .pageId(domain.getPageId())
                .userId(domain.getUserId())
                .fileKey(domain.getFileKey())
                .fileName(domain.getFileName())
                .mime(domain.getMime())
                .size(domain.getSize())
                .created(domain.getCreated())
                .build();
    }
}
