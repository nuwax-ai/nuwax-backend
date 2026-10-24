package com.xspaceagi.repo.web.controller;

import com.xspaceagi.repo.application.service.RepoPageVersionApplicationService;
import com.xspaceagi.repo.application.service.dto.RepoPageVersionListResult;
import com.xspaceagi.repo.domain.model.RepoPageVersionDomain;
import com.xspaceagi.repo.web.dto.RepoPageVersionListVO;
import com.xspaceagi.repo.web.dto.RepoPageVersionPreviewVO;
import com.xspaceagi.repo.web.dto.RepoPageVersionVO;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.stream.Collectors;

/**
 * 文档版本控制器（列表/预览/恢复/手动版本）。
 * <p>
 * 空间成员校验在应用服务内完成；路径与 RepoPageController 同属 /api/repo 登录态命名空间。
 */
@Slf4j
@RestController
@RequestMapping("/api/repo")
@RequiredArgsConstructor
@Tag(name = "Repo Page Version", description = "文档协作：版本历史")
public class RepoPageVersionController {

    private final RepoPageVersionApplicationService repoPageVersionApplicationService;

    @GetMapping("/pages/{pageId}/versions")
    @Operation(summary = "分页查询页面版本列表（倒序，不含大字段）")
    public ReqResult<RepoPageVersionListVO> listVersions(@PathVariable Long pageId,
                                                         @RequestParam(required = false) Integer page,
                                                         @RequestParam(required = false) Integer size) {
        RepoPageVersionListResult result = repoPageVersionApplicationService.listVersions(pageId, page, size);
        RepoPageVersionListVO vo = RepoPageVersionListVO.builder()
                .total(result.getTotal())
                .items(result.getItems().stream()
                        .map(RepoPageVersionController::toVO)
                        .collect(Collectors.toList()))
                .build();
        return ReqResult.success(vo);
    }

    @GetMapping("/pages/{pageId}/versions/{versionId}/preview")
    @Operation(summary = "查询版本HTML快照（预览）")
    public ReqResult<RepoPageVersionPreviewVO> getVersionPreview(@PathVariable Long pageId,
                                                                 @PathVariable Long versionId) {
        RepoPageVersionDomain version = repoPageVersionApplicationService.getVersionPreview(pageId, versionId);
        return ReqResult.success(toPreviewVO(version));
    }

    @PostMapping("/pages/{pageId}/versions/{versionId}/restore")
    @Operation(summary = "恢复版本（覆盖 ydoc_state 并追加 restore 版本记录）")
    public ReqResult<RepoPageVersionVO> restoreVersion(@PathVariable Long pageId,
                                                       @PathVariable Long versionId) {
        RepoPageVersionDomain version = repoPageVersionApplicationService.restoreVersion(pageId, versionId);
        return ReqResult.success(toVO(version));
    }

    @PostMapping("/pages/{pageId}/versions/manual")
    @Operation(summary = "手动保存版本（基于当前 ydoc_state）")
    public ReqResult<RepoPageVersionVO> createManualVersion(@PathVariable Long pageId) {
        RepoPageVersionDomain version = repoPageVersionApplicationService.createManualVersion(pageId);
        return ReqResult.success(toVO(version));
    }

    private static RepoPageVersionVO toVO(RepoPageVersionDomain domain) {
        return RepoPageVersionVO.builder()
                .id(domain.getId())
                .pageId(domain.getPageId())
                .editorId(domain.getEditorId())
                .reason(domain.getReason())
                .created(domain.getCreated())
                .build();
    }

    private static RepoPageVersionPreviewVO toPreviewVO(RepoPageVersionDomain domain) {
        return RepoPageVersionPreviewVO.builder()
                .id(domain.getId())
                .pageId(domain.getPageId())
                .editorId(domain.getEditorId())
                .reason(domain.getReason())
                .created(domain.getCreated())
                .html(domain.getHtml())
                .build();
    }
}
