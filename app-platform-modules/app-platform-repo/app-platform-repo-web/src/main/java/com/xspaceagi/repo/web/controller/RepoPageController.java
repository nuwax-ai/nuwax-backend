package com.xspaceagi.repo.web.controller;

import com.xspaceagi.log.sdk.vo.SearchResult;
import com.xspaceagi.repo.application.search.RepoPageSearchDocument;
import com.xspaceagi.repo.application.service.RepoAccessService;
import com.xspaceagi.repo.application.service.RepoFileUrlService;
import com.xspaceagi.repo.application.service.RepoPageApplicationService;
import com.xspaceagi.repo.application.service.RepoPortalApplicationService;
import com.xspaceagi.repo.application.service.dto.RepoAccessResult;
import com.xspaceagi.repo.application.service.dto.RepoPortalPageVO;
import com.xspaceagi.repo.domain.model.RepoPageDomain;
import com.xspaceagi.repo.domain.model.RepoPageTreeNode;
import com.xspaceagi.repo.domain.model.RepoShareLinkDomain;
import com.xspaceagi.repo.web.dto.RepoPageCreateRequest;
import com.xspaceagi.repo.web.dto.RepoPageMoveRequest;
import com.xspaceagi.repo.web.dto.RepoPageRenameRequest;
import com.xspaceagi.repo.web.dto.RepoPageSearchVO;
import com.xspaceagi.repo.web.dto.RepoPageSnapshotRequest;
import com.xspaceagi.repo.web.dto.RepoPageTreeNodeVO;
import com.xspaceagi.repo.web.dto.RepoPageVO;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 文档页面控制器。
 * <p>
 * 登录认证由平台全局 AuthInterceptor 统一处理，此处无需重复；
 * 空间成员/管理员授权在应用服务内经 SpacePermissionService 校验。
 * <p>
 * 注意：不使用 /api/v1 前缀——该命名空间为 ApiKeyInterceptor 专属（ak-/ck- 开放API），
 * 浏览器 cookie 登录态访问会被 apiKeyMissing 拦截；/api/repo 走标准登录校验。
 */
@Slf4j
@RestController
@RequestMapping("/api/repo")
@RequiredArgsConstructor
@Tag(name = "Repo Page", description = "文档协作：页面管理")
public class RepoPageController {

    private final RepoPageApplicationService repoPageApplicationService;

    private final RepoAccessService repoAccessService;

    private final RepoPortalApplicationService repoPortalApplicationService;

    private final RepoFileUrlService repoFileUrlService;

    private final UserApplicationService userApplicationService;

    @GetMapping("/pages/{pageId}")
    @Operation(summary = "查询页面详情（含当前用户访问权限 myAccess；shareToken 可选，本轮需求4）")
    public ReqResult<RepoPageVO> getPage(@PathVariable Long pageId,
                                         @RequestParam(required = false) String shareToken) {
        RepoPageDomain page = repoPageApplicationService.getPage(pageId, shareToken);
        if (page == null) {
            return ReqResult.error("page not found");
        }
        RepoPageVO vo = toVO(page);
        vo.setMyAccess(repoAccessService.resolveAccess(page, shareToken));
        // 分享者信息（需求1）：持有效链接时回填 ownerId/ownerName，供前端面包屑回落展示分享者
        fillShareOwner(vo, page, shareToken);
        // 门户最近访问：可查看才记录（需求1）
        if (vo.getMyAccess() != null && vo.getMyAccess().isViewable()) {
            repoPortalApplicationService.recordVisit(page.getId(), resolveVisitLinkId(page, shareToken, vo.getMyAccess()));
            // 分享链接进入自动加入「与我共享」（幂等，仅登录用户且权限确由链接授予时生效）
            repoAccessService.recordShareLinkAccess(page, shareToken);
        }
        return ReqResult.success(vo);
    }

    @GetMapping("/s/{slugId}")
    @Operation(summary = "按短链ID查询页面详情（含当前用户访问权限 myAccess；shareToken 可选，本轮需求4）")
    public ReqResult<RepoPageVO> getPageBySlug(@PathVariable String slugId,
                                               @RequestParam(required = false) String shareToken) {
        RepoPageDomain page = repoPageApplicationService.getPageBySlug(slugId, shareToken);
        if (page == null) {
            return ReqResult.error("page not found");
        }
        RepoPageVO vo = toVO(page);
        vo.setMyAccess(repoAccessService.resolveAccess(page, shareToken));
        // 分享者信息（需求1）：持有效链接时回填 ownerId/ownerName，供前端面包屑回落展示分享者
        fillShareOwner(vo, page, shareToken);
        // 门户最近访问：可查看才记录（需求1）
        if (vo.getMyAccess() != null && vo.getMyAccess().isViewable()) {
            repoPortalApplicationService.recordVisit(page.getId(), resolveVisitLinkId(page, shareToken, vo.getMyAccess()));
            // 分享链接进入自动加入「与我共享」（幂等，仅登录用户且权限确由链接授予时生效）
            repoAccessService.recordShareLinkAccess(page, shareToken);
        }
        return ReqResult.success(vo);
    }

    @GetMapping("/pages/recent")
    @Operation(summary = "最近编辑页面（门户首页：成员空间 ∪ 被分享空间，按编辑时间倒序）")
    public ReqResult<List<RepoPageVO>> recentPages(@RequestParam(required = false) Integer size) {
        List<RepoPageVO> pages = repoPageApplicationService.getRecentPages(size == null ? 0 : size).stream()
                .map(this::toVO)
                .collect(Collectors.toList());
        return ReqResult.success(pages);
    }

    @GetMapping("/pages/recently-accessed")
    @Operation(summary = "门户-最近访问（访问记录 ∪ 我编辑过，全部空间，含位置列，按最新时间倒序）")
    public ReqResult<List<RepoPortalPageVO>> recentlyAccessed(@RequestParam(required = false) Integer from,
                                                              @RequestParam(required = false) Integer size) {
        return ReqResult.success(repoPortalApplicationService.listRecentlyAccessed(
                from == null ? 0 : from, size == null ? 0 : size));
    }

    @GetMapping("/pages/my-resources")
    @Operation(summary = "门户-我的资料（我所有成员空间内的页面，按编辑时间倒序分页）")
    public ReqResult<List<RepoPortalPageVO>> myResources(@RequestParam(required = false) Integer from,
                                                         @RequestParam(required = false) Integer size) {
        return ReqResult.success(repoPortalApplicationService.listMyResources(
                from == null ? 0 : from, size == null ? 0 : size));
    }

    @GetMapping("/pages/favorites")
    @Operation(summary = "门户-我的收藏（按收藏时间倒序分页）")
    public ReqResult<List<RepoPortalPageVO>> favorites(@RequestParam(required = false) Integer from,
                                                       @RequestParam(required = false) Integer size) {
        return ReqResult.success(repoPortalApplicationService.listFavorites(
                from == null ? 0 : from, size == null ? 0 : size));
    }

    @GetMapping("/pages/shared-by-me")
    @Operation(summary = "门户-我分享的（我发起的空间分享 ∪ 我创建的链接，按分享时间倒序）")
    public ReqResult<List<RepoPortalPageVO>> sharedByMe() {
        return ReqResult.success(repoPortalApplicationService.listSharedByMe());
    }

    @GetMapping("/pages/shared-with-me")
    @Operation(summary = "门户-与我共享（分享进我的空间的外部页面，按分享时间倒序）")
    public ReqResult<List<RepoPortalPageVO>> sharedWithMe() {
        return ReqResult.success(repoPortalApplicationService.listSharedWithMe());
    }

    @PostMapping("/pages/{pageId}/favorite")
    @Operation(summary = "收藏页面（门户「我的收藏」）")
    public ReqResult<Void> favorite(@PathVariable Long pageId) {
        repoPortalApplicationService.favorite(pageId);
        return ReqResult.success();
    }

    @DeleteMapping("/pages/{pageId}/favorite")
    @Operation(summary = "取消收藏页面（门户「我的收藏」）")
    public ReqResult<Void> unfavorite(@PathVariable Long pageId) {
        repoPortalApplicationService.unfavorite(pageId);
        return ReqResult.success();
    }

    @GetMapping("/pages/{pageId}/favorite")
    @Operation(summary = "查询页面是否已收藏（门户「我的收藏」）")
    public ReqResult<Boolean> isFavorite(@PathVariable Long pageId) {
        return ReqResult.success(repoPortalApplicationService.isFavorite(pageId));
    }

    @GetMapping("/file/access-url")
    @Operation(summary = "文件地址换取免登录直访地址（补 ak / 云存储预签名，需求6）")
    public ReqResult<String> fileAccessUrl(@RequestParam("path") String path, HttpServletRequest request) {
        return ReqResult.success(repoFileUrlService.enrichSingleUrl(path, requestBaseUrl(request)));
    }

    /**
     * 请求来源绝对基址（补文件 ak 用）：优先代理头，兜底直连地址。
     * 与匿名分享控制器同逻辑（预览页与分享页分别持有各自请求）。
     */
    private static String requestBaseUrl(HttpServletRequest request) {
        String forwardedHost = request.getHeader("X-Forwarded-Host");
        String host = forwardedHost != null && !forwardedHost.isBlank()
                ? forwardedHost.split(",")[0].trim() : request.getHeader("Host");
        String scheme = request.getHeader("X-Forwarded-Proto");
        if (scheme == null || scheme.isBlank()) {
            scheme = request.getScheme();
        }
        if (host == null || host.isBlank()) {
            host = request.getServerName() + ":" + request.getServerPort();
        }
        return scheme + "://" + host;
    }

    @DeleteMapping("/pages/{pageId}/visit")
    @Operation(summary = "门户-移除当前用户对页面的访问记录")
    public ReqResult<Void> removeVisit(@PathVariable Long pageId) {
        repoPortalApplicationService.removeVisit(pageId);
        return ReqResult.success();
    }

    @GetMapping("/pages/{pageId}/access")
    @Operation(summary = "查询当前用户对页面的访问权限（前端权限同步；shareToken 可选，本轮需求4）")
    public ReqResult<RepoAccessResult> getAccess(@PathVariable Long pageId,
                                                 @RequestParam(required = false) String shareToken) {
        return ReqResult.success(repoAccessService.getMyAccess(pageId, shareToken));
    }

    @GetMapping("/spaces/{spaceId}/pages")
    @Operation(summary = "查询空间下根节点页面列表")
    public ReqResult<List<RepoPageVO>> listSpaceRootPages(@PathVariable Long spaceId) {
        List<RepoPageVO> pages = repoPageApplicationService.listSpaceRootPages(spaceId).stream()
                .map(this::toVO)
                .collect(Collectors.toList());
        return ReqResult.success(pages);
    }

    @GetMapping("/spaces/{spaceId}/tree")
    @Operation(summary = "查询空间完整页面树")
    public ReqResult<List<RepoPageTreeNodeVO>> getSpaceTree(@PathVariable Long spaceId) {
        List<RepoPageTreeNode> nodes = repoPageApplicationService.getSpaceTree(spaceId);
        // 树中每个节点都要展示创建者/编辑者名字与头像，逐个 queryById 是 N+1（数十上百页即数百次 SQL）。
        // 先把整棵树涉及的所有用户 id 收集去重，一次性批量查，再在转 VO 时从 map 取。
        Map<Long, UserDto> userById = loadTreeUsers(nodes);
        List<RepoPageTreeNodeVO> tree = nodes.stream()
                .map(n -> toTreeVO(n, userById))
                .collect(Collectors.toList());
        return ReqResult.success(tree);
    }

    @PostMapping("/spaces/{spaceId}/pages")
    @Operation(summary = "在空间下新建页面（parentId 为空=根节点）")
    public ReqResult<RepoPageVO> createPage(@PathVariable Long spaceId,
                                            @RequestBody RepoPageCreateRequest request) {
        RepoPageDomain page = repoPageApplicationService.createPage(
                spaceId, request.getParentId(), request.getTitle(), request.getPageType(), request.getSourceExt(),
                request.getOriginalFileKey());
        return ReqResult.success(toVO(page));
    }

    @PostMapping("/pages/{pageId}/copy")
    @Operation(summary = "创建副本（文档复制；限可复制权限，副本为最后保存快照；shareToken 可选；"
            + "targetSpaceId/targetParentId 可选，指定副本落点空间与文件夹，默认源同级末尾）")
    public ReqResult<RepoPageVO> copyPage(@PathVariable Long pageId,
                                          @RequestParam(required = false) String shareToken,
                                          @RequestParam(required = false) Long targetSpaceId,
                                          @RequestParam(required = false) Long targetParentId) {
        RepoPageDomain page = repoPageApplicationService.copyPage(pageId, shareToken, targetSpaceId, targetParentId);
        return ReqResult.success(toVO(page));
    }

    @GetMapping("/pages/{pageId}/children")
    @Operation(summary = "查询页面直接子页面")
    public ReqResult<List<RepoPageVO>> listChildren(@PathVariable Long pageId) {
        List<RepoPageVO> pages = repoPageApplicationService.listChildren(pageId).stream()
                .map(this::toVO)
                .collect(Collectors.toList());
        return ReqResult.success(pages);
    }

    @PutMapping("/pages/{pageId}/title")
    @Operation(summary = "重命名页面")
    public ReqResult<RepoPageVO> renamePage(@PathVariable Long pageId,
                                            @RequestBody RepoPageRenameRequest request) {
        RepoPageDomain page = repoPageApplicationService.renamePage(pageId, request.getTitle());
        return ReqResult.success(toVO(page));
    }

    @PostMapping("/pages/{pageId}/move")
    @Operation(summary = "移动页面（变更父节点/同级排序）")
    public ReqResult<RepoPageVO> movePage(@PathVariable Long pageId,
                                          @RequestBody RepoPageMoveRequest request) {
        RepoPageDomain page = repoPageApplicationService.movePage(
                pageId, request.getTargetParentId(), request.getBeforeId(), request.getAfterId());
        return ReqResult.success(toVO(page));
    }

    @DeleteMapping("/pages/{pageId}")
    @Operation(summary = "软删除页面及其子树（限空间管理员/属主或页面创建者）")
    public ReqResult<Void> deletePage(@PathVariable Long pageId) {
        repoPageApplicationService.deletePage(pageId);
        return ReqResult.success();
    }

    @PostMapping("/pages/{pageId}/snapshot")
    @Operation(summary = "回写内容快照（前端编辑器防抖触发）")
    public ReqResult<RepoPageVO> updateSnapshot(@PathVariable Long pageId,
                                                @RequestBody RepoPageSnapshotRequest request) {
        RepoPageDomain page = repoPageApplicationService.updateSnapshot(pageId, request.getHtml(), request.getContentJson());
        return ReqResult.success(toVO(page));
    }

    @PostMapping("/pages/backfill-media-size")
    @Operation(summary = "存量回填正文媒体字节数（media_size 列，幂等，一次性迁移）")
    public ReqResult<Integer> backfillMediaSize() {
        return ReqResult.success(repoPageApplicationService.backfillMediaSizes());
    }

    @GetMapping("/search")
    @Operation(summary = "按关键字搜索页面（限用户有成员权限的空间）")
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

    public RepoPageVO toVO(RepoPageDomain domain) {
        return toVO(domain, null);
    }

    /**
     * 批量版 toVO：名字/头像从预加载的 userById 取（避免逐页 N+1 查用户表）。
     * userById 传 null 时回退逐个查，供单页端点复用。
     */
    public RepoPageVO toVO(RepoPageDomain domain, Map<Long, UserDto> userById) {
        return RepoPageVO.builder()
                .id(domain.getId())
                .spaceId(domain.getSpaceId())
                .parentId(domain.getParentId())
                .title(domain.getTitle())
                .slugId(domain.getSlugId())
                .position(domain.getPosition())
                .pageType(domain.getPageType())
                .sourceExt(domain.getSourceExt())
                .originalFileKey(domain.getOriginalFileKey())
                .editorIds(domain.getEditorIds())
                .editorNames(userById != null ? namesOf(domain.getEditorIds(), userById)
                        : resolveDisplayNames(domain.getEditorIds()))
                .editorAvatars(userById != null ? avatarsOf(domain.getEditorIds(), userById)
                        : resolveAvatars(domain.getEditorIds()))
                .aclEnabled(domain.getAclEnabled())
                .contentSize(domain.getContentSize() != null ? domain.getContentSize()
                        : (domain.getHtmlSnapshot() == null ? null : (long) domain.getHtmlSnapshot().length()))
                .attachmentSize(domain.getMediaSize())
                .creatorId(domain.getCreatorId())
                .creatorName(userById != null ? nameOf(domain.getCreatorId(), userById)
                        : resolveDisplayName(domain.getCreatorId()))
                .creatorAvatar(userById != null ? avatarOf(domain.getCreatorId(), userById)
                        : resolveAvatar(domain.getCreatorId()))
                .lastEditorId(domain.getLastEditorId())
                .editedAt(domain.getEditedAt())
                .created(domain.getCreated())
                .modified(domain.getModified())
                .build();
    }

    public RepoPageTreeNodeVO toTreeVO(RepoPageTreeNode node) {
        return toTreeVO(node, null);
    }

    public RepoPageTreeNodeVO toTreeVO(RepoPageTreeNode node, Map<Long, UserDto> userById) {
        List<RepoPageTreeNodeVO> children = node.getChildren().stream()
                .map(c -> toTreeVO(c, userById))
                .collect(Collectors.toList());
        return RepoPageTreeNodeVO.builder()
                .page(toVO(node.getPage(), userById))
                .children(children)
                .build();
    }

    /**
     * 由昵称/用户名计算展示名：优先返回用户主动设置的名字（非系统默认格式），再回落系统默认名。
     * 系统默认名形如 "user-1234"（"user-"+手机号后4位，注册时自动生成），会遮盖真实姓名/用户名，
     * 故「非默认格式的用户名」优先于「默认格式的昵称」（如 nick=user-0355、userName=fengfei-01 → 返回 fengfei-01）。
     */
    private static String displayNameOf(String nickName, String userName) {
        String nick = nickName == null || nickName.isBlank() ? null : nickName.trim();
        String name = userName == null || userName.isBlank() ? null : userName.trim();
        if (nick != null && !isSystemDefaultName(nick)) return nick;
        if (name != null && !isSystemDefaultName(name)) return name;
        if (nick != null) return nick;
        if (name != null) return name;
        return null;
    }

    /** 是否为系统自动生成的默认名（"user-"+数字，如 user-0355、user-1315） */
    private static boolean isSystemDefaultName(String s) {
        return s.matches("^user-\\d+$");
    }

    /** 按 userId 查展示名（昵称优先，昵称空用用户名）；用户不存在或名字为空返回 null */
    private String resolveDisplayName(Long userId) {
        if (userId == null) return null;
        UserDto user = userApplicationService.queryById(userId);
        if (user == null) return null;
        return displayNameOf(user.getNickName(), user.getUserName());
    }

    /** 按 userId 查头像原值；用户不存在返回 null */
    private String resolveAvatar(Long userId) {
        if (userId == null) return null;
        UserDto user = userApplicationService.queryById(userId);
        return user == null ? null : user.getAvatar();
    }

    /**
     * 分享者信息回填（需求1）：持有效分享链接访问时，把分享者（链接创建人 created_by）的 id 与展示名写入 VO，
     * 供前端面包屑在「当前用户非源空间成员、拿不到空间名」时回落展示分享者。
     * shareToken 为空或链接无效时不做任何事，保持 ownerId/ownerName 为 null。
     */
    private void fillShareOwner(RepoPageVO vo, RepoPageDomain page, String shareToken) {
        if (shareToken == null || shareToken.isBlank()) return;
        RepoShareLinkDomain link = repoAccessService.findValidShareLink(page.getId(), shareToken, false);
        if (link == null || link.getCreatedBy() == null) return;
        vo.setOwnerId(link.getCreatedBy());
        vo.setOwnerName(resolveDisplayName(link.getCreatedBy()));
    }

    /**
     * 计算「最近访问」的来源链接ID（需求：删除分享链接时级联清理最近访问）。
     * 仅当本次访问权限确由链接授予（via=link，与 recordShareLinkAccess 判定一致）时返回链接ID；
     * 否则返回 null（正常访问，覆盖旧链接来源，使其不受链接删除影响）。
     */
    private Long resolveVisitLinkId(RepoPageDomain page, String shareToken, RepoAccessResult access) {
        if (shareToken == null || shareToken.isBlank() || access == null) return null;
        if (!"link".equals(access.getVia())) return null;
        RepoShareLinkDomain link = repoAccessService.findValidShareLink(page.getId(), shareToken, false);
        return link == null ? null : link.getId();
    }

    /** editorIds → 展示名映射（仅填能查到的；查不到不放入，前端回落「用户 N」） */
    private Map<Long, String> resolveDisplayNames(List<Long> ids) {
        Map<Long, String> map = new HashMap<>();
        if (ids == null) return map;
        for (Long id : ids) {
            String name = resolveDisplayName(id);
            if (name != null) map.put(id, name);
        }
        return map;
    }

    /** editorIds → 头像映射（仅填能查到的） */
    private Map<Long, String> resolveAvatars(List<Long> ids) {
        Map<Long, String> map = new HashMap<>();
        if (ids == null) return map;
        for (Long id : ids) {
            String avatar = resolveAvatar(id);
            if (avatar != null) map.put(id, avatar);
        }
        return map;
    }

    /** 批量版：从预加载的 userById 取展示名（跳过系统默认名，昵称优先） */
    private static String nameOf(Long userId, Map<Long, UserDto> userById) {
        if (userId == null || userById == null) return null;
        UserDto user = userById.get(userId);
        if (user == null) return null;
        return displayNameOf(user.getNickName(), user.getUserName());
    }

    /** 批量版：从预加载的 userById 取头像 */
    private static String avatarOf(Long userId, Map<Long, UserDto> userById) {
        if (userId == null || userById == null) return null;
        UserDto user = userById.get(userId);
        return user == null ? null : user.getAvatar();
    }

    /** 批量版：editorIds → 展示名映射 */
    private static Map<Long, String> namesOf(List<Long> ids, Map<Long, UserDto> userById) {
        Map<Long, String> map = new HashMap<>();
        if (ids == null) return map;
        for (Long id : ids) {
            String name = nameOf(id, userById);
            if (name != null) map.put(id, name);
        }
        return map;
    }

    /** 批量版：editorIds → 头像映射 */
    private static Map<Long, String> avatarsOf(List<Long> ids, Map<Long, UserDto> userById) {
        Map<Long, String> map = new HashMap<>();
        if (ids == null) return map;
        for (Long id : ids) {
            String avatar = avatarOf(id, userById);
            if (avatar != null) map.put(id, avatar);
        }
        return map;
    }

    /**
     * 收集整棵树涉及的创建者/最后编辑者/编辑者 id，一次性批量查用户表，
     * 返回 id → UserDto 映射；查询失败返回空 map 不阻断（前端回落「用户 N」）。
     */
    private Map<Long, UserDto> loadTreeUsers(List<RepoPageTreeNode> nodes) {
        Set<Long> ids = new HashSet<>();
        collectTreeUserIds(nodes, ids);
        Map<Long, UserDto> byId = new HashMap<>();
        if (ids.isEmpty()) {
            return byId;
        }
        try {
            List<UserDto> users = userApplicationService.queryUserListByIds(new ArrayList<>(ids));
            if (users != null) {
                for (UserDto user : users) {
                    if (user.getId() != null) {
                        byId.put(user.getId(), user);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("repo tree load users failed, ids={}", ids, e);
        }
        return byId;
    }

    private void collectTreeUserIds(List<RepoPageTreeNode> nodes, Set<Long> ids) {
        if (nodes == null) {
            return;
        }
        for (RepoPageTreeNode node : nodes) {
            RepoPageDomain page = node.getPage();
            if (page.getCreatorId() != null) {
                ids.add(page.getCreatorId());
            }
            if (page.getLastEditorId() != null) {
                ids.add(page.getLastEditorId());
            }
            if (page.getEditorIds() != null) {
                ids.addAll(page.getEditorIds());
            }
            collectTreeUserIds(node.getChildren(), ids);
        }
    }
}
