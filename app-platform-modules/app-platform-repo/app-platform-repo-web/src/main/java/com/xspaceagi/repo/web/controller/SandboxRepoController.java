package com.xspaceagi.repo.web.controller;

import cn.hutool.json.JSONUtil;
import com.xspaceagi.file.application.service.FileManagementService;
import com.xspaceagi.file.domain.model.FileRecordDomain;
import com.xspaceagi.log.sdk.vo.SearchResult;
import com.xspaceagi.repo.application.search.RepoPageSearchDocument;
import com.xspaceagi.repo.application.service.*;
import com.xspaceagi.repo.application.service.dto.*;
import com.xspaceagi.repo.domain.model.*;
import com.xspaceagi.repo.web.client.RepoCollabSidecarClient;
import com.xspaceagi.repo.web.dto.*;
import com.xspaceagi.repo.web.dto.sidecar.SidecarAiEditResult;
import com.xspaceagi.repo.web.dto.sidecar.SidecarImportRequest;
import com.xspaceagi.repo.web.dto.sidecar.SidecarImportResult;
import com.xspaceagi.system.application.dto.SpaceDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.AuthService;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.sdk.service.dto.UserAccessKeyDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.exception.BizException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 沙箱文档 API（面向 AI agent 开放的资料库操作面）。
 * <p>
 * 统一前缀 {@code /api/v1/4sandbox/repo}，仅 GET/POST。认证走 {@code Authorization: Bearer ${SANDBOX_ACCESS_KEY}}：
 * 平台 {@code ApiKeyInterceptor} 识别 AKTargetType.Sandbox 且路径以
 * {@code /api/v1/4sandbox} 开头即放行，并把 sandbox 用户写入 {@link RequestContext}。租户隔离沿用
 * Host / {@code x-tenant-id} 头解析（与其它 4sandbox 控制器同款）。
 * <p>
 * 端点分两类：
 * <ul>
 *   <li>平台 CRUD/元数据/搜索/导出（docx/pdf/markdown/original）——直接复用 repo 应用服务；</li>
 *   <li>导入 / 读正文 / 定点改正文 / xlsx+pptx 导出——经 {@link RepoCollabSidecarClient} 用共享密钥转发
 *       Sidecar（nuwax-repo-collab :9090）内部端点。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/4sandbox/repo")
@RequiredArgsConstructor
@Tag(name = "Sandbox Repo", description = "沙箱文档 API：资料库导入/导出/增删改查")
public class SandboxRepoController {

    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final String PPTX_MIME =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation";

    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private static final String TICKET_CLIENT_ID = "sandbox-repo";

    private final RepoPageApplicationService repoPageApplicationService;

    private final RepoAccessService repoAccessService;

    private final RepoDocConvertService repoDocConvertService;

    private final RepoDocExportService repoDocExportService;

    private final FileManagementService fileManagementService;

    private final SpaceApplicationService spaceApplicationService;

    private final AuthService authService;

    private final RepoCollabSidecarClient sidecarClient;

    private final RepoPageController repoPageController;

    private final RepoCommentApplicationService repoCommentApplicationService;

    private final RepoShareApplicationService repoShareApplicationService;

    private final RepoPageVersionApplicationService repoPageVersionApplicationService;

    private final RepoAttachmentApplicationService repoAttachmentApplicationService;

    private final UserApplicationService userApplicationService;

    // ============================ 空间 ============================

    @GetMapping("/spaces")
    @Operation(summary = "当前 sandbox 用户的全部空间列表")
    public ReqResult<List<SpaceDto>> spaces() {
        Long userId = RequestContext.get().getUserId();
        UserAccessKeyDto userAccessKeyDto = (UserAccessKeyDto) RequestContext.get().getUserAccessKey();
        Map<?, ?> map = extMapOf(userAccessKeyDto);
        if (map != null && map.containsKey("GROUP_SENDER_ID") && map.containsKey("SPACE_ID")) {
            List<SpaceDto> spaces = spaceApplicationService.queryByIds(List.of(Long.parseLong(map.get("SPACE_ID").toString())));
            return ReqResult.success(spaces);
        }
        return ReqResult.success(spaceApplicationService.queryListByUserId(userId));
    }

    private static Map<?, ?> extMapOf(UserAccessKeyDto userAccessKey) {
        if (userAccessKey == null || userAccessKey.getConfig() == null) {
            return null;
        }
        Object ext = userAccessKey.getConfig().getExt();
        return ext instanceof Map<?, ?> extMap ? extMap : null;
    }

    @GetMapping("/spaces/{spaceId}/tree")
    @Operation(summary = "空间完整页面树")
    public ReqResult<List<RepoPageTreeNodeVO>> spaceTree(@PathVariable Long spaceId) {
        List<RepoPageTreeNodeVO> tree = repoPageApplicationService.getSpaceTree(spaceId).stream()
                .map(repoPageController::toTreeVO)
                .collect(Collectors.toList());
        return ReqResult.success(tree);
    }

    @GetMapping("/spaces/{spaceId}/pages")
    @Operation(summary = "空间根节点页面列表")
    public ReqResult<List<RepoPageVO>> spaceRootPages(@PathVariable Long spaceId) {
        List<RepoPageVO> pages = repoPageApplicationService.listSpaceRootPages(spaceId).stream()
                .map(repoPageController::toVO)
                .collect(Collectors.toList());
        return ReqResult.success(pages);
    }

    // ============================ 页面元数据 ============================

    @GetMapping("/pages/{pageId}")
    @Operation(summary = "按 ID 读取页面元数据")
    public ReqResult<RepoPageVO> getPage(@PathVariable Long pageId) {
        RepoPageDomain page = repoPageApplicationService.getPage(pageId);
        if (page == null) {
            return ReqResult.error("page not found");
        }
        return ReqResult.success(repoPageController.toVO(page));
    }

    @GetMapping("/pages/by-slug/{slugId}")
    @Operation(summary = "按短链 ID 读取页面元数据")
    public ReqResult<RepoPageVO> getPageBySlug(@PathVariable String slugId) {
        RepoPageDomain page = repoPageApplicationService.getPageBySlug(slugId);
        if (page == null) {
            return ReqResult.error("page not found");
        }
        return ReqResult.success(repoPageController.toVO(page));
    }

    // ============================ 正文读写 ============================

    @GetMapping("/pages/{pageId}/content")
    @Operation(summary = "读正文：按文档类型返回 doc(富文本 JSON) / slides(幻灯片模型) / snapshot(表格快照) 三选一")
    public ReqResult<SidecarAiEditResult> readContent(@PathVariable Long pageId) {
        RepoPageDomain page = requirePage(pageId);
        return ReqResult.success(sidecarClient.readContent(docName(page), kindOf(page)));
    }

    @PostMapping("/pages/{pageId}/content")
    @Operation(summary = "定点修改正文（kind 缺省按页面类型映射；ops 结构化指令数组按序执行）")
    public ReqResult<SidecarAiEditResult> editContent(@PathVariable Long pageId,
                                                      @RequestBody SandboxContentEditRequest request) {
        RepoPageDomain page = requirePage(pageId);
        repoAccessService.requireEditable(page);
        String kind = request.getKind() != null ? request.getKind() : kindOf(page);
        SidecarAiEditResult result = sidecarClient.editContent(
                docName(page), kind, RequestContext.get().getUserId(), request.getOps(),
                pageId, newTicket());
        return ReqResult.success(result);
    }

    // ============================ 导出 ============================

    @GetMapping("/pages/{pageId}/export")
    @Operation(summary = "导出页面：xlsx/pptx 走 Sidecar，docx/pdf/markdown/original 走平台服务")
    public ResponseEntity<?> export(@PathVariable Long pageId, @RequestParam String format) {
        RepoPageDomain page = repoPageApplicationService.getPage(pageId);
        if (page == null) {
            return ResponseEntity.notFound().build();
        }
        repoAccessService.requireExportable(page);
        String fmt = format == null ? "" : format.toLowerCase(Locale.ROOT);
        switch (fmt) {
            case "xlsx", "pptx" -> {
                byte[] bytes = sidecarClient.export(docName(page), fmt, page.getTitle(), newTicket());
                return download(bytes, page.getTitle() + "." + fmt,
                        "xlsx".equals(fmt) ? XLSX_MIME : PPTX_MIME);
            }
            case "docx" -> {
                byte[] bytes = repoDocExportService.exportDocx(page);
                return download(bytes, page.getTitle() + ".docx", DOCX_MIME);
            }
            case "pdf" -> {
                byte[] bytes = repoDocExportService.exportPdf(page);
                return download(bytes, page.getTitle() + ".pdf", MediaType.APPLICATION_PDF_VALUE);
            }
            case "markdown" -> {
                String markdown = repoDocConvertService.htmlToMarkdown(page.getHtmlSnapshot());
                RepoPageExportVO vo = RepoPageExportVO.builder()
                        .title(page.getTitle())
                        .markdown(markdown)
                        .build();
                return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(ReqResult.success(vo));
            }
            case "original" -> {
                return exportOriginal(page);
            }
            default -> {
                return ResponseEntity.badRequest().build();
            }
        }
    }

    // ============================ 搜索 ============================

    @GetMapping("/search")
    @Operation(summary = "按关键字搜索（限用户有成员权限的空间）")
    public ReqResult<List<RepoPageSearchVO>> search(@RequestParam(required = false) Long spaceId,
                                                    @RequestParam(required = false) String keyword,
                                                    @RequestParam(required = false) Integer from,
                                                    @RequestParam(required = false) Integer size) {
        SearchResult result = repoPageApplicationService.searchPages(spaceId, keyword, from, size);
        List<RepoPageSearchVO> items = new ArrayList<>();
        if (result != null && result.getItems() != null) {
            for (SearchResult.SearchResultItem item : result.getItems()) {
                if (item.getDocument() instanceof RepoPageSearchDocument) {
                    items.add(toSearchVO(item.getScore(), (RepoPageSearchDocument) item.getDocument()));
                }
            }
        }
        return ReqResult.success(items);
    }

    // ============================ 写操作 ============================

    @PostMapping("/pages")
    @Operation(summary = "新建页面（spaceId 必填；parentId/title/pageType/sourceExt/originalFileKey 可选）")
    public ReqResult<RepoPageVO> createPage(@RequestBody SandboxPageCreateRequest request) {
        RepoPageDomain page = repoPageApplicationService.createPage(
                request.getSpaceId(), request.getParentId(), request.getTitle(), request.getPageType(),
                request.getSourceExt(), request.getOriginalFileKey());
        // 树结构变更广播已下沉到 RepoPageApplicationService（SDK 与 HTTP 路径统一），此处不再重复。
        return ReqResult.success(repoPageController.toVO(page));
    }

    @PostMapping("/pages/{pageId}/rename")
    @Operation(summary = "重命名页面（body {title}）")
    public ReqResult<RepoPageVO> renamePage(@PathVariable Long pageId,
                                            @RequestBody RepoPageRenameRequest request) {
        requirePage(pageId);
        RepoPageDomain page = repoPageApplicationService.renamePage(pageId, request.getTitle());
        // 广播标题变更给在线协同者：写 Sidecar 内存 Y.Doc meta.docTitle → Hocuspocus 广播 →
        // 编辑器监听后走既有 repo:title-remote-synced 链路刷新标题/树。
        // 标题权威仍在 repo_page（本端点已持久化）；setDocTitle 仅发变更信号，不二次写库/回写快照。
        // 广播为尽力而为：失败仅缺一次实时同步，不阻断改名（标题已持久化）。
        broadcastTitleChange(page, pageId, request.getTitle());
        return ReqResult.success(repoPageController.toVO(page));
    }

    /**
     * 标题变更广播（尽力而为）：通过 Sidecar /internal/ai-edit 的 setDocTitle 指令写
     * Sidecar 内存 Y.Doc 的 meta.docTitle，借 Hocuspocus 广播同步给所有在线编辑者。
     * kind/pageId/ticket 不传：setDocTitle 与文档类型无关，且不触发 doc 快照回写。
     */
    private void broadcastTitleChange(RepoPageDomain page, Long pageId, String title) {
        try {
            sidecarClient.editContent(docName(page), null, null,
                    List.of(Map.of("op", "setDocTitle", "title", title)), null, null);
        } catch (Exception e) {
            log.warn("broadcast title failed: pageId={}, error={}", pageId, e.getMessage());
        }
    }

    /**
     * 树结构变更广播（尽力而为）：通过 Sidecar /internal/tree-changed 把 {spaceId} 写进全部内存文档的
     * Yjs meta.treeVersion，借 Hocuspocus 广播给开着同空间文档的协同者，驱其实时刷新左侧目录树。
     * <p>
     * 仅剩「导入」这条 web 入口在用：create/delete/move/copy/rename 的树变更广播已下沉到
     * {@code RepoPageApplicationService}（application 层 {@link com.xspaceagi.repo.application.collab.RepoTreeChangeNotifier}，
     * SDK 与 HTTP 路径统一），此处不再重复。导入由 Sidecar 建页、不经 application service，故仍在此广播。
     */
    private void broadcastTreeChange(Long spaceId) {
        try {
            sidecarClient.treeChanged(spaceId);
        } catch (Exception e) {
            log.warn("broadcast tree-changed failed: spaceId={}, error={}", spaceId, e.getMessage());
        }
    }

    @PostMapping("/pages/{pageId}/delete")
    @Operation(summary = "软删除页面及其子树")
    public ReqResult<Void> deletePage(@PathVariable Long pageId) {
        RepoPageDomain page = requirePage(pageId);
        repoPageApplicationService.deletePage(pageId);
        // 树结构变更广播已下沉到 RepoPageApplicationService（SDK 与 HTTP 路径统一），此处不再重复。
        return ReqResult.success();
    }

    @PostMapping("/import")
    @Operation(summary = "导入文档（text/markdown/fileKey/fileName 等，Sidecar 深度解析并创建页面）")
    public ReqResult<SidecarImportResult> importDoc(@RequestBody SidecarImportRequest request) {
        request.setTicket(newTicket());
        SidecarImportResult result = sidecarClient.importDoc(request);
        // 广播树结构变更：导入成功后开着同空间文档的协同者实时刷新目录树。尽力而为，失败不阻断导入。
        broadcastTreeChange(request.getSpaceId());
        return ReqResult.success(result);
    }

    // ============================ 目录管理 ============================

    @GetMapping("/pages/{pageId}/children")
    @Operation(summary = "查询页面/文件夹的直接子页面（含子文件夹）")
    public ReqResult<List<RepoPageVO>> listChildren(@PathVariable Long pageId) {
        List<RepoPageVO> pages = repoPageApplicationService.listChildren(pageId).stream()
                .map(repoPageController::toVO)
                .collect(Collectors.toList());
        return ReqResult.success(pages);
    }

    @PostMapping("/pages/{pageId}/move")
    @Operation(summary = "移动页面/文件夹（body {targetParentId,beforeId,afterId}）")
    public ReqResult<RepoPageVO> movePage(@PathVariable Long pageId,
                                          @RequestBody RepoPageMoveRequest request) {
        RepoPageDomain page = repoPageApplicationService.movePage(
                pageId, request.getTargetParentId(), request.getBeforeId(), request.getAfterId());
        // 树结构变更广播已下沉到 RepoPageApplicationService（SDK 与 HTTP 路径统一），此处不再重复。
        return ReqResult.success(repoPageController.toVO(page));
    }

    @PostMapping("/pages/{pageId}/copy")
    @Operation(summary = "复制文档（query targetSpaceId/targetParentId 可选，默认源同级末尾）")
    public ReqResult<RepoPageVO> copyPage(@PathVariable Long pageId,
                                          @RequestParam(required = false) Long targetSpaceId,
                                          @RequestParam(required = false) Long targetParentId) {
        RepoPageDomain page = repoPageApplicationService.copyPage(pageId, null, targetSpaceId, targetParentId);
        // 树结构变更广播已下沉到 RepoPageApplicationService（SDK 与 HTTP 路径统一），此处不再重复。
        return ReqResult.success(repoPageController.toVO(page));
    }

    // ============================ 评论 ============================

    @GetMapping("/pages/{pageId}/comments")
    @Operation(summary = "查询页面评论线程列表")
    public ReqResult<List<RepoCommentThreadVO>> listComments(@PathVariable Long pageId) {
        List<RepoCommentThread> domainThreads = repoCommentApplicationService.listCommentThreads(pageId, null);
        Map<Long, UserDto> userById = loadCommentUsers(collectCommentUserIds(domainThreads));
        List<RepoCommentThreadVO> threads = domainThreads.stream()
                .map(thread -> RepoCommentThreadVO.builder()
                        .comment(toCommentVO(thread.getComment(), userById))
                        .replies(thread.getReplies().stream()
                                .map(reply -> toCommentVO(reply, userById))
                                .collect(Collectors.toList()))
                        .build())
                .collect(Collectors.toList());
        return ReqResult.success(threads);
    }

    @PostMapping("/pages/{pageId}/comments")
    @Operation(summary = "发表评论或回复（一层回复，@提及发送站内通知）")
    public ReqResult<RepoCommentVO> addComment(@PathVariable Long pageId,
                                               @RequestBody RepoCommentCreateRequest request) {
        RepoCommentDomain comment = repoCommentApplicationService.addComment(
                pageId, request.getParentId(), request.getContent(), request.getMentions(), request.getAnchor(), null);
        return ReqResult.success(toCommentVO(comment, loadCommentUser(comment.getUserId())));
    }

    @PostMapping("/comments/{commentId}/resolve")
    @Operation(summary = "标记评论已解决")
    public ReqResult<RepoCommentVO> resolveComment(@PathVariable Long commentId) {
        RepoCommentDomain comment = repoCommentApplicationService.resolveComment(commentId, null);
        return ReqResult.success(toCommentVO(comment, loadCommentUser(comment.getUserId())));
    }

    @PostMapping("/comments/{commentId}/delete")
    @Operation(summary = "软删除评论及其回复")
    public ReqResult<Void> deleteComment(@PathVariable Long commentId) {
        repoCommentApplicationService.deleteComment(commentId, null);
        return ReqResult.success();
    }

    // ============================ 分享 ============================

    @GetMapping("/users/search")
    @Operation(summary = "按用户名/昵称搜索用户（分享给个人选人用，排除自己，上限20条）")
    public ReqResult<List<RepoUserSearchVO>> searchUsers(@RequestParam String kw,
                                                         @RequestParam(defaultValue = "10") Integer size) {
        int limit = size == null || size <= 0 ? 10 : Math.min(size, 20);
        Long selfId = RequestContext.get() == null ? null : RequestContext.get().getUserId();
        List<RepoUserSearchVO> result = new ArrayList<>();
        for (UserDto user : userApplicationService.searchUsers(kw, limit)) {
            if (user.getId() != null && user.getId().equals(selfId)) {
                continue;
            }
            result.add(RepoUserSearchVO.builder()
                    .id(user.getId())
                    .userName(user.getUserName())
                    .nickName(user.getNickName())
                    .avatar(user.getAvatar())
                    .build());
        }
        return ReqResult.success(result);
    }

    @GetMapping("/pages/{pageId}/shares")
    @Operation(summary = "查询文档分享状况（空间分享+链接+管理标志）")
    public ReqResult<RepoShareStatus> getShareStatus(@PathVariable Long pageId) {
        return ReqResult.success(repoShareApplicationService.getShareStatus(pageId));
    }

    @PostMapping("/pages/{pageId}/shares/spaces")
    @Operation(summary = "分享文档到工作空间（body {targetSpaceIds,permBits}）")
    public ReqResult<Void> shareToSpaces(@PathVariable Long pageId,
                                         @RequestBody RepoShareSpacesRequest request) {
        repoShareApplicationService.shareToSpaces(pageId, request.getTargetSpaceIds(), request.getPermBits());
        return ReqResult.success();
    }

    @PostMapping("/pages/{pageId}/shares/users")
    @Operation(summary = "分享文档给个人（body {targetUserIds,permBits}）")
    public ReqResult<Void> shareToUsers(@PathVariable Long pageId,
                                        @RequestBody RepoShareUsersRequest request) {
        repoShareApplicationService.shareToUsers(pageId, request.getTargetUserIds(), request.getPermBits());
        return ReqResult.success();
    }

    @GetMapping("/spaces/{spaceId}/shares/received")
    @Operation(summary = "空间主页「接收分享」列表")
    public ReqResult<List<RepoSpaceShareItemVO>> listSpaceReceivedShares(@PathVariable Long spaceId) {
        return ReqResult.success(repoShareApplicationService.listSpaceReceivedShares(spaceId));
    }

    @GetMapping("/spaces/{spaceId}/shares/sent")
    @Operation(summary = "空间主页「对外分享」列表")
    public ReqResult<List<RepoSpaceShareItemVO>> listSpaceSentShares(@PathVariable Long spaceId) {
        return ReqResult.success(repoShareApplicationService.listSpaceSentShares(spaceId));
    }

    @PostMapping("/pages/{pageId}/shares/links")
    @Operation(summary = "新建分享链接（body {accessType,allowComment,permBits}）")
    public ReqResult<RepoShareLinkVO> createShareLink(@PathVariable Long pageId,
                                                      @RequestBody RepoShareLinkCreateRequest request) {
        var link = repoShareApplicationService.createShareLink(
                pageId, request.getAccessType(), request.getAllowComment(), request.getPermBits());
        return ReqResult.success(RepoShareLinkVO.from(link));
    }

    @PostMapping("/shares/links/{linkId}/comment")
    @Operation(summary = "更新分享链接的评论权限（query pageId/allowComment）")
    public ReqResult<Void> updateShareLinkComment(@PathVariable Long linkId,
                                                  @RequestParam Long pageId,
                                                  @RequestParam Integer allowComment) {
        repoShareApplicationService.updateShareLinkComment(pageId, linkId, allowComment);
        return ReqResult.success();
    }

    @PostMapping("/shares/links/{linkId}/delete")
    @Operation(summary = "删除分享链接（query pageId）")
    public ReqResult<Void> deleteShareLink(@PathVariable Long linkId, @RequestParam Long pageId) {
        repoShareApplicationService.deleteShareLink(pageId, linkId);
        return ReqResult.success();
    }

    @PostMapping("/shares/{shareId}/delete")
    @Operation(summary = "取消单条空间分享（query pageId）")
    public ReqResult<Void> unshare(@PathVariable Long shareId, @RequestParam Long pageId) {
        repoShareApplicationService.unshare(pageId, shareId);
        return ReqResult.success();
    }

    @PostMapping("/shares/users/{shareUserId}/delete")
    @Operation(summary = "取消单条个人分享（query pageId）")
    public ReqResult<Void> unshareUser(@PathVariable Long shareUserId, @RequestParam Long pageId) {
        repoShareApplicationService.unshareUser(pageId, shareUserId);
        return ReqResult.success();
    }

    @PostMapping("/pages/{pageId}/shares/unshare-all")
    @Operation(summary = "取消该文档全部分享与链接")
    public ReqResult<Void> unshareAll(@PathVariable Long pageId) {
        repoShareApplicationService.unshareAll(pageId);
        return ReqResult.success();
    }

    @GetMapping("/pages/{pageId}/share-links/{linkId}/stats")
    @Operation(summary = "分享链接访问统计（访问次数+去重访问用户）")
    public ReqResult<RepoShareLinkStatsVO> getShareLinkStats(@PathVariable Long pageId,
                                                             @PathVariable Long linkId) {
        return ReqResult.success(repoShareApplicationService.getShareLinkStats(pageId, linkId));
    }

    // ============================ 版本 ============================

    @GetMapping("/pages/{pageId}/versions")
    @Operation(summary = "分页查询页面版本列表（倒序，不含大字段）")
    public ReqResult<RepoPageVersionListVO> listVersions(@PathVariable Long pageId,
                                                         @RequestParam(required = false) Integer page,
                                                         @RequestParam(required = false) Integer size) {
        RepoPageVersionListResult result = repoPageVersionApplicationService.listVersions(pageId, page, size);
        RepoPageVersionListVO vo = RepoPageVersionListVO.builder()
                .total(result.getTotal())
                .items(result.getItems().stream()
                        .map(SandboxRepoController::toVersionVO)
                        .collect(Collectors.toList()))
                .build();
        return ReqResult.success(vo);
    }

    @GetMapping("/pages/{pageId}/versions/{versionId}/preview")
    @Operation(summary = "查询版本 HTML 快照（预览）")
    public ReqResult<RepoPageVersionPreviewVO> getVersionPreview(@PathVariable Long pageId,
                                                                 @PathVariable Long versionId) {
        RepoPageVersionDomain version = repoPageVersionApplicationService.getVersionPreview(pageId, versionId);
        return ReqResult.success(toVersionPreviewVO(version));
    }

    @PostMapping("/pages/{pageId}/versions/{versionId}/restore")
    @Operation(summary = "恢复版本（覆盖 ydoc_state 并追加 restore 版本记录）")
    public ReqResult<RepoPageVersionVO> restoreVersion(@PathVariable Long pageId,
                                                       @PathVariable Long versionId) {
        RepoPageVersionDomain version = repoPageVersionApplicationService.restoreVersion(pageId, versionId);
        return ReqResult.success(toVersionVO(version));
    }

    @PostMapping("/pages/{pageId}/versions/manual")
    @Operation(summary = "手动保存版本（基于当前 ydoc_state）")
    public ReqResult<RepoPageVersionVO> createManualVersion(@PathVariable Long pageId) {
        RepoPageVersionDomain version = repoPageVersionApplicationService.createManualVersion(pageId);
        return ReqResult.success(toVersionVO(version));
    }

    // ============================ 附件 ============================

    @PostMapping("/pages/{pageId}/attachments")
    @Operation(summary = "挂接页面级附件")
    public ReqResult<RepoAttachmentVO> addPageAttachment(@PathVariable Long pageId,
                                                         @RequestBody RepoAttachmentCreateRequest request) {
        RepoAttachmentDomain attachment = repoAttachmentApplicationService.addPageAttachment(
                pageId, request.getFileKey(), request.getFileName(), request.getMime(), request.getSize());
        return ReqResult.success(toAttachmentVO(attachment));
    }

    @PostMapping("/spaces/{spaceId}/attachments")
    @Operation(summary = "挂接空间级附件")
    public ReqResult<RepoAttachmentVO> addSpaceAttachment(@PathVariable Long spaceId,
                                                          @RequestBody RepoAttachmentCreateRequest request) {
        RepoAttachmentDomain attachment = repoAttachmentApplicationService.addSpaceAttachment(
                spaceId, request.getFileKey(), request.getFileName(), request.getMime(), request.getSize());
        return ReqResult.success(toAttachmentVO(attachment));
    }

    @GetMapping("/pages/{pageId}/attachments")
    @Operation(summary = "查询页面附件列表")
    public ReqResult<List<RepoAttachmentVO>> listPageAttachments(@PathVariable Long pageId) {
        return ReqResult.success(repoAttachmentApplicationService.listPageAttachments(pageId).stream()
                .map(SandboxRepoController::toAttachmentVO)
                .collect(Collectors.toList()));
    }

    @GetMapping("/spaces/{spaceId}/attachments")
    @Operation(summary = "查询空间全部附件（含页面级与空间级）")
    public ReqResult<List<RepoAttachmentVO>> listSpaceAttachments(@PathVariable Long spaceId) {
        return ReqResult.success(repoAttachmentApplicationService.listSpaceAttachments(spaceId).stream()
                .map(SandboxRepoController::toAttachmentVO)
                .collect(Collectors.toList()));
    }

    @PostMapping("/attachments/{attachmentId}/delete")
    @Operation(summary = "软删除附件")
    public ReqResult<Void> deleteAttachment(@PathVariable Long attachmentId) {
        repoAttachmentApplicationService.deleteAttachment(attachmentId);
        return ReqResult.success();
    }

    // ============================ 文档级权限（ACL） ============================

    @GetMapping("/pages/{pageId}/acl")
    @Operation(summary = "查询文档级按人权限状况（开关+条目）")
    public ReqResult<RepoAclStatus> getAcl(@PathVariable Long pageId) {
        return ReqResult.success(repoShareApplicationService.getAcl(pageId));
    }

    @PostMapping("/pages/{pageId}/acl")
    @Operation(summary = "整体覆盖文档级按人权限")
    public ReqResult<Void> putAcl(@PathVariable Long pageId,
                                  @RequestBody RepoPageAclPutRequest request) {
        List<RepoPageAclDomain> entries = new ArrayList<>();
        if (request.getEntries() != null) {
            for (RepoPageAclPutRequest.Entry entry : request.getEntries()) {
                entries.add(RepoPageAclDomain.builder()
                        .pageId(pageId)
                        .userId(entry.getUserId())
                        .permBits(entry.getPermBits())
                        .build());
            }
        }
        repoShareApplicationService.putAcl(pageId, request.getAclEnabled(), entries);
        return ReqResult.success();
    }

    // ============================ 辅助 ============================

    private RepoPageDomain requirePage(Long pageId) {
        RepoPageDomain page = repoPageApplicationService.getPage(pageId);
        if (page == null) {
            throw new BizException("page not found");
        }
        return page;
    }

    /**
     * Sidecar 文档定位名：{@code page:<slugId>}
     */
    private String docName(RepoPageDomain page) {
        return "page:" + page.getSlugId();
    }

    /**
     * 按页面类型映射正文 kind：sheet→sheet，datatable→sheet（底层同构），pptx(靠 sourceExt)→pptx，其余 doc。避免全新空表按内容探测误判成 doc。
     */
    private String kindOf(RepoPageDomain page) {
        if ("sheet".equals(page.getPageType()) || "datatable".equals(page.getPageType())) {
            return "sheet";
        }
        if ("pptx".equalsIgnoreCase(page.getSourceExt())) {
            return "pptx";
        }
        return "doc";
    }

    /**
     * 为当前 sandbox 用户签发新 JWT（供 Sidecar 回调平台创建页面/拉取媒体）
     */
    private String newTicket() {
        UserDto user = (UserDto) RequestContext.get().getUser();
        if (user == null || user.getId() == null) {
            throw new BizException("sandbox user missing");
        }
        return RequestContext.get().getToken() != null ? RequestContext.get().getToken() : authService.createToken(user, TICKET_CLIENT_ID);
    }

    /**
     * 下载原始导入文件；无原件的 html/htm 导入回退为下载快照 .html。逻辑与
     * {@link RepoDocConvertController#exportOriginal} 一致（此处独立实现，避免依赖 controller 私有方法）。
     */
    private ResponseEntity<byte[]> exportOriginal(RepoPageDomain page) {
        String fileKey = page.getOriginalFileKey();
        if (fileKey != null && !fileKey.isBlank()) {
            try (InputStream in = fileManagementService.downloadFile(fileKey)) {
                byte[] bytes = in == null ? null : in.readAllBytes();
                if (bytes != null && bytes.length > 0) {
                    String fileName = page.getTitle();
                    String contentType = guessContentType(fileKey);
                    try {
                        FileRecordDomain record = fileManagementService.getFileByKey(fileKey);
                        if (record != null && record.getFileName() != null && !record.getFileName().isBlank()) {
                            fileName = record.getFileName();
                            contentType = guessContentType(record.getFileName());
                        }
                    } catch (Exception e) {
                        log.debug("sandbox export original getFileByKey failed, fileKey={}", fileKey);
                    }
                    return download(bytes, fileName, contentType);
                }
            } catch (Exception e) {
                log.warn("sandbox export original download failed, pageId={}, fileKey={}, error={}",
                        page.getId(), fileKey, e.getMessage());
            }
        }
        String ext = page.getSourceExt() == null ? "" : page.getSourceExt().toLowerCase(Locale.ROOT);
        String titleLower = page.getTitle() == null ? "" : page.getTitle().toLowerCase(Locale.ROOT);
        boolean isHtmlDoc = "html".equals(ext) || "htm".equals(ext)
                || titleLower.endsWith(".html") || titleLower.endsWith(".htm");
        if (isHtmlDoc) {
            byte[] bytes = (page.getHtmlSnapshot() == null ? "" : page.getHtmlSnapshot())
                    .getBytes(StandardCharsets.UTF_8);
            String baseTitle = page.getTitle() == null ? "page" : page.getTitle();
            String fileName = titleLower.endsWith(".html") || titleLower.endsWith(".htm")
                    ? baseTitle : baseTitle + ".html";
            return download(bytes, fileName, "text/html; charset=UTF-8");
        }
        return ResponseEntity.badRequest().build();
    }

    private String guessContentType(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pptx")) {
            return PPTX_MIME;
        }
        if (lower.endsWith(".docx")) {
            return DOCX_MIME;
        }
        if (lower.endsWith(".xlsx")) {
            return XLSX_MIME;
        }
        if (lower.endsWith(".pdf")) {
            return MediaType.APPLICATION_PDF_VALUE;
        }
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "text/html; charset=UTF-8";
        }
        if (lower.endsWith(".md") || lower.endsWith(".txt")) {
            return "text/plain; charset=UTF-8";
        }
        if (lower.endsWith(".csv")) {
            return "text/csv; charset=UTF-8";
        }
        if (lower.endsWith(".json")) {
            return "application/json; charset=UTF-8";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        return MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }

    private ResponseEntity<byte[]> download(byte[] bytes, String fileName, String contentType) {
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"download\"; filename*=UTF-8''" + encoded)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(bytes.length)
                .body(bytes);
    }

    private static RepoPageSearchVO toSearchVO(Double score, RepoPageSearchDocument doc) {
        String snippet = doc.getContent();
        if (snippet != null && snippet.length() > 200) {
            snippet = snippet.substring(0, 200);
        }
        return RepoPageSearchVO.builder()
                .score(score)
                .pageId(doc.getPageId())
                .spaceId(doc.getSpaceId())
                .slugId(doc.getSlugId())
                .title(doc.getTitle())
                .snippet(snippet)
                .lastEditorId(doc.getLastEditorId())
                .editedAt(doc.getEditedAt())
                .build();
    }

    /**
     * 评论 VO：解析 @提及 id 列表 + 回填用户昵称/用户名/头像（userById 查不到留空，前端回落「用户 N」）
     */
    private RepoCommentVO toCommentVO(RepoCommentDomain domain, Map<Long, UserDto> userById) {
        List<Long> mentions = new ArrayList<>();
        if (domain.getMentions() != null && JSONUtil.isTypeJSONArray(domain.getMentions())) {
            mentions = JSONUtil.toList(domain.getMentions(), Long.class);
        }
        UserDto user = userById != null ? userById.get(domain.getUserId()) : null;
        return RepoCommentVO.builder()
                .id(domain.getId())
                .pageId(domain.getPageId())
                .userId(domain.getUserId())
                .nickName(user != null ? user.getNickName() : null)
                .userName(user != null ? user.getUserName() : null)
                .avatar(user != null ? user.getAvatar() : null)
                .parentId(domain.getParentId())
                .content(domain.getContent())
                .mentions(mentions)
                .anchor(domain.getAnchor())
                .resolved(domain.getResolved())
                .created(domain.getCreated())
                .modified(domain.getModified())
                .build();
    }

    /**
     * 收集线程内所有评论/回复的 userId（含空值防护）
     */
    private Set<Long> collectCommentUserIds(List<RepoCommentThread> threads) {
        Set<Long> ids = new HashSet<>();
        if (threads == null) return ids;
        for (RepoCommentThread thread : threads) {
            RepoCommentDomain comment = thread.getComment();
            if (comment != null && comment.getUserId() != null) ids.add(comment.getUserId());
            if (thread.getReplies() != null) {
                for (RepoCommentDomain reply : thread.getReplies()) {
                    if (reply.getUserId() != null) ids.add(reply.getUserId());
                }
            }
        }
        return ids;
    }

    /**
     * 批量查用户表返回 id → UserDto；查询失败返回空 map 不阻断（前端回落「用户 N」）
     */
    private Map<Long, UserDto> loadCommentUsers(Set<Long> ids) {
        Map<Long, UserDto> byId = new HashMap<>();
        if (ids == null || ids.isEmpty()) return byId;
        try {
            List<UserDto> users = userApplicationService.queryUserListByIds(new ArrayList<>(ids));
            if (users != null) {
                for (UserDto user : users) {
                    if (user.getId() != null) byId.put(user.getId(), user);
                }
            }
        } catch (Exception e) {
            log.warn("sandbox comment load users failed, ids={}", ids, e);
        }
        return byId;
    }

    /**
     * 单条评论的用户解析（发表/解决后即时返回）
     */
    private Map<Long, UserDto> loadCommentUser(Long userId) {
        Set<Long> ids = new HashSet<>();
        if (userId != null) ids.add(userId);
        return loadCommentUsers(ids);
    }

    private static RepoPageVersionVO toVersionVO(RepoPageVersionDomain domain) {
        return RepoPageVersionVO.builder()
                .id(domain.getId())
                .pageId(domain.getPageId())
                .editorId(domain.getEditorId())
                .reason(domain.getReason())
                .created(domain.getCreated())
                .build();
    }

    private static RepoPageVersionPreviewVO toVersionPreviewVO(RepoPageVersionDomain domain) {
        return RepoPageVersionPreviewVO.builder()
                .id(domain.getId())
                .pageId(domain.getPageId())
                .editorId(domain.getEditorId())
                .reason(domain.getReason())
                .created(domain.getCreated())
                .html(domain.getHtml())
                .build();
    }

    private static RepoAttachmentVO toAttachmentVO(RepoAttachmentDomain domain) {
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
