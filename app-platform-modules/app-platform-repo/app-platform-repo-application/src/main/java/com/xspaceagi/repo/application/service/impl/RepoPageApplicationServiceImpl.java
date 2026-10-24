package com.xspaceagi.repo.application.service.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.xspaceagi.file.application.service.FileManagementService;
import com.xspaceagi.file.domain.model.FileRecordDomain;
import com.xspaceagi.log.sdk.vo.SearchResult;
import com.xspaceagi.repo.application.collab.RepoTreeChangeNotifier;
import com.xspaceagi.repo.application.search.RepoPageSearchDocument;
import com.xspaceagi.repo.application.search.RepoPageSearchService;
import com.xspaceagi.repo.application.service.RepoAccessService;
import com.xspaceagi.repo.application.service.RepoPageApplicationService;
import com.xspaceagi.repo.domain.model.RepoAttachmentDomain;
import com.xspaceagi.repo.domain.model.RepoPageDomain;
import com.xspaceagi.repo.domain.model.RepoPageTreeNode;
import com.xspaceagi.repo.domain.model.RepoShareDomain;
import com.xspaceagi.repo.domain.repository.RepoAttachmentRepository;
import com.xspaceagi.repo.domain.repository.RepoPageRepository;
import com.xspaceagi.repo.domain.repository.RepoShareLinkRepository;
import com.xspaceagi.repo.domain.repository.RepoShareRepository;
import com.xspaceagi.repo.domain.repository.RepoShareUserRepository;
import com.xspaceagi.repo.spec.constants.RepoConstants;
import com.xspaceagi.repo.spec.enums.RepoPageTypeEnum;
import com.xspaceagi.repo.spec.utils.PositionUtils;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 文档页面应用服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepoPageApplicationServiceImpl implements RepoPageApplicationService {

    /**
     * slug 字符集（去掉易混淆的 0/O/1/l/I）
     */
    private static final char[] SLUG_ALPHABET = "23456789abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 标题最大长度（对齐 repo_page.title VARCHAR(500)）
     */
    private static final int TITLE_MAX_LENGTH = 500;

    /**
     * 导入源扩展名最大长度（对齐 repo_page.source_ext VARCHAR(16)，预留余量）
     */
    private static final int SOURCE_EXT_MAX_LENGTH = 16;

    /**
     * 最近编辑默认条数（需求6门户首页）
     */
    private static final int RECENT_DEFAULT_SIZE = 12;

    /**
     * 最近编辑条数上限
     */
    private static final int RECENT_MAX_SIZE = 50;

    /**
     * 平台文件访问地址前缀，「/api/f/{fileKey}」（fileKey 自身含斜杠）
     */
    private static final String FILE_URL_PREFIX = "/api/f/";

    /**
     * 删除页面时物理删文件的 key 数量上限。每个 key 需全表扫一次引用，超限只软删记录不动文件，
     * 避免超大文档删除时阻塞过久（文件残留可由存储侧清理，比误删安全）。
     */
    private static final int DELETE_FILE_KEY_LIMIT = 200;

    /**
     * 删除页面时逐页取正文扫文件 key 的子树规模上限。超限放弃文件清理只软删记录，
     * 避免删大文件夹时把几十 MB 正文全拉进内存。
     */
    private static final int DELETE_PAGE_SCAN_LIMIT = 100;

    private final RepoPageRepository repoPageRepository;

    private final FileManagementService fileManagementService;

    private final SpacePermissionService spacePermissionService;

    private final RepoPageSearchService repoPageSearchService;

    private final RepoAccessService repoAccessService;

    private final RepoShareRepository repoShareRepository;

    private final RepoShareUserRepository repoShareUserRepository;

    private final RepoShareLinkRepository repoShareLinkRepository;

    private final RepoAttachmentRepository repoAttachmentRepository;

    private final RepoTreeChangeNotifier repoTreeChangeNotifier;

    @Override
    public RepoPageDomain getPage(Long pageId) {
        return getPage(pageId, null);
    }

    @Override
    public RepoPageDomain getPage(Long pageId, String shareToken) {
        RepoPageDomain page = repoPageRepository.findById(pageId);
        if (page != null) {
            // 统一访问裁决：无查看权限抛 SpacePermissionException（全局处理转 403）；
            // shareToken 供经链接进入的登录用户透传（本轮需求4链接权限）
            repoAccessService.requireViewable(page, shareToken);
        }
        return page;
    }

    @Override
    public RepoPageDomain getPageBySlug(String slugId) {
        return getPageBySlug(slugId, null);
    }

    @Override
    public RepoPageDomain getPageBySlug(String slugId, String shareToken) {
        RepoPageDomain page = repoPageRepository.findBySlugId(slugId);
        if (page != null) {
            repoAccessService.requireViewable(page, shareToken);
        }
        return page;
    }

    @Override
    public List<RepoPageDomain> listSpaceRootPages(Long spaceId) {
        // 空间成员校验复用平台 SpacePermissionService，无权限时抛业务异常
        spacePermissionService.checkSpaceUserPermission(spaceId);
        List<RepoPageDomain> pages = repoPageRepository.listRootsBySpaceId(spaceId);
        return pages;
    }

    @Override
    public RepoPageDomain createPage(Long spaceId, Long parentId, String title, String pageType, String sourceExt,
                                     String originalFileKey) {
        String normalizedPageType = normalizePageType(pageType);

        if (parentId != null) {
            // 在已有页面下建子页：要求对父页面有编辑权限（含被分享编辑权限场景）
            RepoPageDomain parent = repoPageRepository.findById(parentId);
            if (parent == null || !Objects.equals(parent.getSpaceId(), spaceId)) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound);
            }
            repoAccessService.requireEditable(parent);
        } else {
            // 根节点创建：至少为空间成员
            spacePermissionService.checkSpaceUserPermission(spaceId);
        }

        // 追加到同级末尾
        List<RepoPageDomain> siblings = listSiblings(spaceId, parentId);
        String position = siblings.isEmpty()
                ? PositionUtils.between(null, null)
                : PositionUtils.between(siblings.get(siblings.size() - 1).getPosition(), null);

        Long userId = RequestContext.get().getUserId();
        Long tenantId = RequestContext.get().getTenantId();
        Date now = new Date();

        RepoPageDomain domain = RepoPageDomain.builder()
                .tenantId(tenantId)
                .spaceId(spaceId)
                .parentId(parentId)
                .title(normalizeTitle(title))
                .slugId(generateSlugId())
                .position(position)
                .pageType(normalizedPageType)
                .creatorId(userId)
                .lastEditorId(userId)
                .editedAt(now)
                .sourceExt(normalizeSourceExt(sourceExt))
                .originalFileKey(originalFileKey == null || originalFileKey.isBlank() ? null : originalFileKey)
                .build();

        Long id = repoPageRepository.insert(domain);
        domain.setId(id);
        domain.setCreated(now);
        domain.setModified(now);
        log.info("repo page created: id={}, spaceId={}, parentId={}, slugId={}, userId={}",
                id, spaceId, parentId, domain.getSlugId(), userId);
        // 新建即索引（此时正文为空，仅标题可搜）；文件夹为纯容器不进搜索索引
        if (!RepoPageTypeEnum.FOLDER.getCode().equals(normalizedPageType)) {
            repoPageSearchService.indexPage(domain);
        }
        repoTreeChangeNotifier.notifyTreeChanged(spaceId);
        return domain;
    }

    @Override
    public RepoPageDomain copyPage(Long pageId) {
        return copyPage(pageId, null);
    }

    @Override
    public RepoPageDomain copyPage(Long pageId, String shareToken) {
        return copyPage(pageId, shareToken, null, null);
    }

    @Override
    public RepoPageDomain copyPage(Long pageId, String shareToken, Long targetSpaceId, Long targetParentId) {
        RepoPageDomain src = mustGetPage(pageId);
        repoAccessService.requireCopyable(src, shareToken);

        // 目标空间：未传则沿用源空间（同级末尾，兼容旧调用）；传了则校验目标空间成员
        Long spaceId = targetSpaceId == null ? src.getSpaceId() : targetSpaceId;
        if (!Objects.equals(spaceId, src.getSpaceId())) {
            spacePermissionService.checkSpaceUserPermission(spaceId);
        }

        // 目标父页（文件夹）：非空则须属于目标空间且可编辑
        Long parentId = targetParentId;
        if (parentId != null) {
            RepoPageDomain parent = repoPageRepository.findById(parentId);
            if (parent == null || !Objects.equals(parent.getSpaceId(), spaceId)) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound);
            }
            repoAccessService.requireEditable(parent);
        }

        return doCopyPage(src, spaceId, parentId);
    }

    private RepoPageDomain doCopyPage(RepoPageDomain src, Long spaceId, Long parentId) {
        Long pageId = src.getId();
        // 追加到目标（空间, 父级）末尾（与 createPage 同模式）
        List<RepoPageDomain> siblings = listSiblings(spaceId, parentId);
        String position = siblings.isEmpty()
                ? PositionUtils.between(null, null)
                : PositionUtils.between(siblings.get(siblings.size() - 1).getPosition(), null);

        Long userId = RequestContext.get().getUserId();
        Date now = new Date();

        // 内容快照原样拷贝；ydoc 单独单列读写（domain 不承载 ydocState）
        RepoPageDomain copy = RepoPageDomain.builder()
                .tenantId(src.getTenantId())
                .spaceId(spaceId)
                .parentId(parentId)
                .title(normalizeTitle((src.getTitle() == null ? "" : src.getTitle()) + "(副本)"))
                .slugId(generateSlugId())
                .position(position)
                .pageType(src.getPageType())
                .contentSnapshot(src.getContentSnapshot())
                .htmlSnapshot(src.getHtmlSnapshot())
                .creatorId(userId)
                .lastEditorId(userId)
                .editedAt(now)
                .sourceExt(src.getSourceExt())
                .originalFileKey(src.getOriginalFileKey())
                .mediaSize(src.getMediaSize() != null && src.getMediaSize() > 0
                        ? src.getMediaSize()
                        : computeMediaSize(src.getId(), src.getContentSnapshot(), src.getHtmlSnapshot(),
                                src.getOriginalFileKey()))
                .editorIds(List.of(userId))
                .build();

        Long id = repoPageRepository.insert(copy);
        copy.setId(id);
        copy.setCreated(now);
        copy.setModified(now);

        // CRDT 权威源原样拷贝（复用版本恢复的单列读写；源页无 ydoc 则跳过，副本首开为空文档同新建）
        byte[] ydoc = repoPageRepository.findYdocStateById(pageId);
        if (ydoc != null && ydoc.length > 0) {
            repoPageRepository.updateYdocState(id, ydoc, userId, now);
        }

        log.info("repo page copied: srcId={}, copyId={}, slugId={}, spaceId={}, parentId={}, userId={}",
                pageId, id, copy.getSlugId(), spaceId, parentId, userId);
        repoPageSearchService.indexPage(copy);
        repoTreeChangeNotifier.notifyTreeChanged(spaceId);
        return copy;
    }

    @Override
    public RepoPageDomain renamePage(Long pageId, String title) {
        RepoPageDomain page = mustGetPage(pageId);
        repoAccessService.requireEditable(page);

        String normalized = normalizeTitle(title);
        if (!repoPageRepository.updateTitle(pageId, normalized)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound);
        }
        page.setTitle(normalized);
        repoPageSearchService.indexPage(page);
        repoTreeChangeNotifier.notifyTreeChanged(page.getSpaceId());
        return page;
    }

    @Override
    public void deletePage(Long pageId) {
        RepoPageDomain page = mustGetPage(pageId);
        // 删除限源空间管理员/属主或页面创建者（需求4明确）
        repoAccessService.requireDeletable(page);

        // 收集页面及其全部子孙，一并软删
        List<RepoPageDomain> all = repoPageRepository.listBySpaceId(page.getSpaceId());
        List<Long> ids = collectSubtreeIds(all, pageId);
        int affected = cascadeDeletePageIds(ids);
        log.info("repo page deleted: id={}, spaceId={}, subtreeSize={}, affected={}",
                pageId, page.getSpaceId(), ids.size(), affected);
        repoTreeChangeNotifier.notifyTreeChanged(page.getSpaceId());
    }

    @Override
    public int deleteBySpaceId(Long spaceId) {
        List<RepoPageDomain> pages = repoPageRepository.listBySpaceId(spaceId);
        if (pages == null || pages.isEmpty()) {
            return 0;
        }
        List<Long> ids = new ArrayList<>();
        for (RepoPageDomain p : pages) {
            if (p.getId() != null) {
                ids.add(p.getId());
            }
        }
        int affected = cascadeDeletePageIds(ids);
        log.info("repo space pages deleted: spaceId={}, pageCount={}, affected={}", spaceId, ids.size(), affected);
        return affected;
    }

    /**
     * 级联清理一批页面：软删 + 清分享/分享链接/分享用户 + 清附件 + 删无引用文件 + 删搜索索引。
     * 失败仅告警（页面已软删对用户不可见，文件/索引残留不影响功能）。
     *
     * @param ids 待删除页面ID集合（含子孙）
     * @return 软删受影响行数
     */
    private int cascadeDeletePageIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        // 软删前先把待删子树引用的文件 key 收齐（软删后正文查不到，无从回溯）
        Set<String> fileKeys = collectSubtreeFileKeys(ids);
        int affected = repoPageRepository.softDeleteByIds(ids);
        // 级联清理分享记录与分享链接（失败仅告警，页面已软删不可见）
        for (Long id : ids) {
            try {
                repoShareRepository.softDeleteByPageId(id);
                repoShareUserRepository.softDeleteByPageId(id);
                repoShareLinkRepository.softDeleteByPageId(id);
            } catch (Exception e) {
                log.warn("repo share cleanup failed on page delete, pageId={}", id, e);
            }
        }
        // 附件记录软删 + 无其他页面引用的文件物理删除
        try {
            repoAttachmentRepository.softDeleteByPageIds(ids);
        } catch (Exception e) {
            log.warn("repo attachment cleanup failed on page delete, ids={}", ids, e);
        }
        deleteUnreferencedFiles(fileKeys, ids);
        // 同步清理搜索索引（失败仅告警）
        repoPageSearchService.deletePages(ids);
        return affected;
    }

    @Override
    public RepoPageDomain movePage(Long pageId, Long targetParentId, Long beforeId, Long afterId) {
        RepoPageDomain page = mustGetPage(pageId);
        repoAccessService.requireEditable(page);

        // 目标父节点校验：存在、同空间
        if (targetParentId != null) {
            if (Objects.equals(targetParentId, pageId)) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                        "cannot move page under itself");
            }
            RepoPageDomain parent = repoPageRepository.findById(targetParentId);
            if (parent == null || !Objects.equals(parent.getSpaceId(), page.getSpaceId())) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound);
            }
        }

        // 防止成环：目标父节点不能是被移动页面的子孙
        List<RepoPageDomain> all = repoPageRepository.listBySpaceId(page.getSpaceId());
        Set<Long> subtree = new HashSet<>(collectSubtreeIds(all, pageId));
        if (targetParentId != null && subtree.contains(targetParentId)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                    "cannot move page under its own descendant");
        }

        String position = resolveMovePosition(page, targetParentId, beforeId, afterId);
        if (!repoPageRepository.updateParentAndPosition(pageId, targetParentId, position)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound);
        }
        page.setParentId(targetParentId);
        page.setPosition(position);
        log.info("repo page moved: id={}, targetParentId={}, position={}", pageId, targetParentId, position);
        repoTreeChangeNotifier.notifyTreeChanged(page.getSpaceId());
        return page;
    }

    @Override
    public List<RepoPageDomain> listChildren(Long pageId) {
        RepoPageDomain page = mustGetPage(pageId);
        repoAccessService.requireViewable(page);
        List<RepoPageDomain> children = repoPageRepository.listChildren(page.getSpaceId(), pageId);
        return children;
    }

    @Override
    public List<RepoPageTreeNode> getSpaceTree(Long spaceId) {
        spacePermissionService.checkSpaceUserPermission(spaceId);
        List<RepoPageDomain> all = repoPageRepository.listBySpaceId(spaceId);
        return buildTree(all);
    }

    @Override
    public RepoPageDomain updateSnapshot(Long pageId, String html, String contentJson) {
        RepoPageDomain page = mustGetPage(pageId);
        // 快照回写由编辑器前端发起，要求可编辑（文档级按人权限/分享权限统一裁决）
        repoAccessService.requireEditable(page);

        Long userId = RequestContext.get().getUserId();
        Date now = new Date();
        // 维护历史编辑者列表（需求5）：追加当前用户，去重，上限裁剪最早的
        List<Long> editorIds = appendEditorId(page.getEditorIds(), userId);
        String editorIdsJson = editorIds.isEmpty() ? null : JSONUtil.toJsonStr(editorIds);
        // 媒体大小：四路收集文件 key（正文节点 / HTML 快照 / 导入原件 / 附件表）去重后按文件表求和，
        // 落库 media_size 列，供列表/树轻量查询直接取列（不拉正文大字段）
        long mediaSize = computeMediaSize(pageId, contentJson, html, page.getOriginalFileKey());
        if (!repoPageRepository.updateSnapshot(pageId, contentJson, html, mediaSize, userId, now, editorIdsJson)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound);
        }
        page.setContentSnapshot(contentJson);
        page.setHtmlSnapshot(html);
        page.setMediaSize(mediaSize);
        page.setLastEditorId(userId);
        page.setEditedAt(now);
        page.setEditorIds(editorIds);
        repoPageSearchService.indexPage(page);
        log.info("repo page snapshot updated: id={}, spaceId={}, userId={}", pageId, page.getSpaceId(), userId);
        return page;
    }

    @Override
    public SearchResult searchPages(Long spaceId, String keyword, Integer from, Integer size) {
        Long userId = RequestContext.get().getUserId();
        // 指定空间时先验成员资格，非成员直接拒绝（防搜索探测空间内容）
        if (spaceId != null && spacePermissionService.getSpaceUserRole(spaceId, userId) == null) {
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
        }
        SearchResult result = repoPageSearchService.search(spaceId, keyword, from, size);
        if (spaceId != null || result == null || result.getItems() == null || result.getItems().isEmpty()) {
            return result;
        }
        // 全租户搜索：按成员资格或被分享后置过滤（空间成员 或 页面被分享到用户所在空间）
        List<SearchResult.SearchResultItem> visible = new ArrayList<>();
        List<SearchResult.SearchResultItem> candidates = new ArrayList<>();
        Map<Long, Boolean> memberCache = new HashMap<>();
        for (SearchResult.SearchResultItem item : result.getItems()) {
            if (!(item.getDocument() instanceof RepoPageSearchDocument)) {
                continue;
            }
            RepoPageSearchDocument doc = (RepoPageSearchDocument) item.getDocument();
            boolean member = memberCache.computeIfAbsent(doc.getSpaceId(),
                    sid -> spacePermissionService.getSpaceUserRole(sid, userId) != null);
            if (member) {
                visible.add(item);
            } else {
                candidates.add(item);
            }
        }
        // 非成员空间的命中页面：查分享记录命中则可见
        if (!candidates.isEmpty()) {
            List<Long> mySpaceIds = spacePermissionService.querySpaceIdList(userId);
            if (mySpaceIds != null && !mySpaceIds.isEmpty()) {
                List<Long> candidatePageIds = new ArrayList<>();
                for (SearchResult.SearchResultItem item : candidates) {
                    candidatePageIds.add(((RepoPageSearchDocument) item.getDocument()).getPageId());
                }
                Set<Long> sharedPageIds = new HashSet<>();
                for (RepoShareDomain share : repoShareRepository.listByPageIdsAndTargetSpaceIds(candidatePageIds, mySpaceIds)) {
                    sharedPageIds.add(share.getPageId());
                }
                for (SearchResult.SearchResultItem item : candidates) {
                    if (sharedPageIds.contains(((RepoPageSearchDocument) item.getDocument()).getPageId())) {
                        visible.add(item);
                    }
                }
            }
        }
        return SearchResult.builder().total(result.getTotal()).items(visible).build();
    }

    @Override
    public List<RepoPageDomain> getRecentPages(int size) {
        Long userId = RequestContext.get().getUserId();
        List<Long> spaceIds = spacePermissionService.querySpaceIdList(userId);
        if (spaceIds == null || spaceIds.isEmpty()) {
            return new ArrayList<>();
        }
        int limit = size <= 0 ? RECENT_DEFAULT_SIZE : Math.min(size, RECENT_MAX_SIZE);
        return repoPageRepository.listRecent(spaceIds, limit);
    }

    /**
     * 计算页面媒体大小（字节）：四路收集平台文件 key 后去重，统一按 key 回查文件表求和。
     * <p>
     * 四路来源缺一不可：
     * ① content_snapshot 的 image/attachment/video/audio 节点 src —— doc 类正文；
     * ② html_snapshot 内的「/api/f/{key}」—— pptx 正文在 ydoc_state 的 slidesModel，
     * HTML 是 renderSlidesToHtml 产物且 img 不带 size，只能从 HTML 扫；sheet 同理；
     * ③ original_file_key —— 导入的 PPTX/DOCX/PDF 原件本体；仅当 ①② 未解析出任何媒体时
     * 才计入（原件与解析媒体是同一份内容的两种存储，叠加会把大小算成约 2 倍）；
     * ④ repo_attachment —— 附件映射表。
     * <p>
     * 以「文件表按 key 求和」为唯一口径，同一文件被多路命中只算一次；
     * 无平台 key 的媒体节点（base64 内嵌/外链）退回 attrs.size 累加。
     */
    private long computeMediaSize(Long pageId, String contentJson, String html, String originalFileKey) {
        MediaScan scan = new MediaScan();
        if (contentJson != null && !contentJson.isBlank()) {
            try {
                scanMediaNode(JSONUtil.parse(contentJson), scan);
            } catch (Exception ignored) {
                // 非 ProseMirror JSON（sheet 走 opLog）或解析失败，按无正文媒体处理
            }
        }
        // HTML 快照兜底扫描：覆盖 pptx/sheet，也补齐 doc 正文里未被节点识别的引用
        collectFileKeysFromText(html, scan.keys);
        String original = normalizeFileKey(originalFileKey);
        // 原件（original_file_key）与从原件深度解析出的正文媒体，是同一份内容的两种存储：
        // 导入 docx/pptx/xlsx 时原件本身已含图片，解析又把内嵌图片单独上传成独立文件，
        // 若再把原件叠加进 media_size，一份内容会被算成约 2 倍。故仅当正文没有解析出任何
        // 媒体（scan.keys 为空且无 base64 内嵌）时——如 PDF 原样预览、无图纯文本导入——
        // 原件才是该文档唯一存储实体，此时才计入；否则原件视为冗余副本，不叠加。
        if (original != null && scan.keys.isEmpty() && scan.sizeWithoutKey == 0) {
            scan.keys.add(original);
        }
        // 附件映射表：同时留一份 key->size 兜底，文件表查不到时用附件记录的 size
        Map<String, Long> attachmentSizes = new HashMap<>();
        if (pageId != null) {
            try {
                List<RepoAttachmentDomain> attachments = repoAttachmentRepository.listByPageId(pageId);
                if (attachments != null) {
                    for (RepoAttachmentDomain attachment : attachments) {
                        String key = normalizeFileKey(attachment.getFileKey());
                        if (key == null) {
                            continue;
                        }
                        scan.keys.add(key);
                        if (attachment.getSize() != null && attachment.getSize() > 0) {
                            attachmentSizes.put(key, attachment.getSize());
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("repo attachment lookup failed on media size compute, pageId={}", pageId, e);
            }
        }
        return scan.sizeWithoutKey + sumFileSizes(scan.keys, attachmentSizes);
    }

    /**
     * 正文媒体扫描结果：命中平台文件 key 的进 keys（按文件表统一求和去重），
     * 无 key 的（base64/外链）就地累加 attrs.size。
     */
    private static final class MediaScan {

        private final Set<String> keys = new HashSet<>();

        private long sizeWithoutKey;
    }

    /**
     * 递归扫描 ProseMirror JSON 的媒体节点。媒体可能是 inline 形态嵌在段落内，也可能在
     * 表格/列表/块引用等任意层级，故按各节点 content 数组全量递归。
     */
    private static void scanMediaNode(Object node, MediaScan scan) {
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            String type = obj.getStr("type");
            if ("image".equals(type) || "attachment".equals(type) || "video".equals(type)
                    || "audio".equals(type) || "media".equals(type)) {
                JSONObject attrs = obj.getJSONObject("attrs");
                String key = attrs == null ? null : extractFileKey(attrs.getStr("src"));
                if (key == null && attrs != null) {
                    key = normalizeFileKey(attrs.getStr("fileKey"));
                }
                if (key != null) {
                    scan.keys.add(key);
                } else if (attrs != null) {
                    Long size = attrs.getLong("size");
                    scan.sizeWithoutKey += size == null || size < 0 ? 0L : size;
                }
            }
            JSONArray content = obj.getJSONArray("content");
            if (content != null) {
                for (Object child : content) {
                    scanMediaNode(child, scan);
                }
            }
        } else if (node instanceof JSONArray) {
            for (Object child : (JSONArray) node) {
                scanMediaNode(child, scan);
            }
        }
    }

    /**
     * 扫描任意文本（HTML 快照/正文 JSON 串）中的全部「/api/f/{key}」引用。
     */
    private static void collectFileKeysFromText(String text, Set<String> keys) {
        if (text == null || text.isBlank()) {
            return;
        }
        int from = 0;
        while (true) {
            int idx = text.indexOf(FILE_URL_PREFIX, from);
            if (idx < 0) {
                return;
            }
            int start = idx + FILE_URL_PREFIX.length();
            String key = readFileKey(text, start);
            if (key != null) {
                keys.add(key);
            }
            from = start;
        }
    }

    /**
     * 从媒体 src 抽取平台文件 key。src 形态为「/api/f/{fileKey}」或带域名/查询串的等价 URL；
     * 注意 fileKey 自身含斜杠（如 s3/default/20260902/xxx.jpeg），不可按第一个斜杠截断。
     */
    private static String extractFileKey(String src) {
        if (src == null) {
            return null;
        }
        int idx = src.indexOf(FILE_URL_PREFIX);
        if (idx < 0) {
            return null;
        }
        return readFileKey(src, idx + FILE_URL_PREFIX.length());
    }

    /**
     * 从 start 起按字符白名单读出文件 key，遇到 ? # 引号 空白 尖括号 等分隔符即止。
     */
    private static String readFileKey(String text, int start) {
        int end = start;
        while (end < text.length()) {
            char c = text.charAt(end);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '/' || c == '.' || c == '_' || c == '-' || c == '+' || c == '%' || c == '=' || c == '~';
            if (!allowed) {
                break;
            }
            end++;
        }
        String key = text.substring(start, end);
        return key.isBlank() ? null : key;
    }

    /**
     * 归一化文件 key：允许传入裸 key 或含 /api/f/ 前缀的 URL，空白返回 null。
     */
    private static String normalizeFileKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.contains(FILE_URL_PREFIX)) {
            return extractFileKey(trimmed);
        }
        return trimmed;
    }

    /**
     * 按文件 key 回查文件表求和。查不到（文件已删/记录缺失）时用 fallback 里的大小兜底；
     * 单个 key 失败不阻断整页统计。
     */
    private long sumFileSizes(Set<String> keys, Map<String, Long> fallback) {
        long total = 0L;
        for (String key : keys) {
            Long size = null;
            try {
                FileRecordDomain record = fileManagementService.getFileByKey(key);
                if (record != null) {
                    size = record.getFileSize();
                }
            } catch (Exception ignored) {
                // 单个文件回查失败按未知大小处理
            }
            if ((size == null || size <= 0) && fallback != null) {
                size = fallback.get(key);
            }
            total += size == null || size < 0 ? 0L : size;
        }
        return total;
    }

    /**
     * 收集待删子树引用的全部平台文件 key，来源与 media_size 统计同源（正文节点/HTML 快照/
     * 导入原件/附件表），保证「统计进大小的文件」和「删除时清理的文件」是同一批。
     * <p>
     * 逐页 findById 取正文而非一次性拉全子树：正文列合计可达数十 MB。
     * 子树超 {@link #DELETE_PAGE_SCAN_LIMIT} 页时放弃收集（只软删记录不动文件），
     * 文件残留可由存储侧清理，比阻塞删除请求划算。
     */
    private Set<String> collectSubtreeFileKeys(List<Long> ids) {
        Set<String> keys = new HashSet<>();
        if (ids == null || ids.isEmpty() || ids.size() > DELETE_PAGE_SCAN_LIMIT) {
            return keys;
        }
        for (Long id : ids) {
            try {
                RepoPageDomain target = repoPageRepository.findById(id);
                if (target == null) {
                    continue;
                }
                MediaScan scan = new MediaScan();
                String contentJson = target.getContentSnapshot();
                if (contentJson != null && !contentJson.isBlank()) {
                    try {
                        scanMediaNode(JSONUtil.parse(contentJson), scan);
                    } catch (Exception ignored) {
                        // 非 ProseMirror JSON（sheet 走 opLog），按无正文媒体处理
                    }
                }
                keys.addAll(scan.keys);
                collectFileKeysFromText(target.getHtmlSnapshot(), keys);
                String original = normalizeFileKey(target.getOriginalFileKey());
                if (original != null) {
                    keys.add(original);
                }
                List<RepoAttachmentDomain> attachments = repoAttachmentRepository.listByPageId(id);
                if (attachments != null) {
                    for (RepoAttachmentDomain attachment : attachments) {
                        String key = normalizeFileKey(attachment.getFileKey());
                        if (key != null) {
                            keys.add(key);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("collect file keys failed on page delete, pageId={}", id, e);
            }
        }
        return keys;
    }

    /**
     * 物理删除「只被本次待删子树引用」的文件。
     * <p>
     * 必须先筛引用再删：copyPage 原样拷贝 originalFileKey 与快照，副本与源页共享同一批 key，
     * 直接删会把副本的图片一起弄坏。key 数超 {@link #DELETE_FILE_KEY_LIMIT} 时跳过物理删除
     * （每批引用判定都要扫一次正文列），只留记录软删。
     * <p>
     * 同步执行而非异步：异步线程拿不到 RequestContext，租户条件注入会失效。
     * 全程失败仅告警——页面已软删对用户不可见，文件残留不影响功能。
     */
    private void deleteUnreferencedFiles(Set<String> fileKeys, List<Long> excludeIds) {
        if (fileKeys == null || fileKeys.isEmpty()) {
            return;
        }
        if (fileKeys.size() > DELETE_FILE_KEY_LIMIT) {
            log.info("skip physical file delete, too many keys: size={}, limit={}",
                    fileKeys.size(), DELETE_FILE_KEY_LIMIT);
            return;
        }
        try {
            Set<String> stillReferenced = repoPageRepository.filterReferencedFileKeys(fileKeys, excludeIds);
            List<Long> fileIds = new ArrayList<>();
            for (String key : fileKeys) {
                if (stillReferenced.contains(key)) {
                    continue;
                }
                try {
                    FileRecordDomain record = fileManagementService.getFileByKey(key);
                    if (record != null && record.getId() != null) {
                        fileIds.add(record.getId());
                    }
                } catch (Exception e) {
                    log.warn("file lookup failed on page delete, fileKey={}", key, e);
                }
            }
            if (fileIds.isEmpty()) {
                return;
            }
            fileManagementService.batchDeleteFiles(fileIds);
            log.info("repo page delete removed files: candidates={}, referenced={}, deleted={}",
                    fileKeys.size(), stillReferenced.size(), fileIds.size());
        } catch (Exception e) {
            log.warn("physical file cleanup failed on page delete, keys={}", fileKeys.size(), e);
        }
    }

    /**
     * 存量回填：遍历全部有效页面重算 media_size。只取主键再逐页取正文，
     * 避免一次性拉全库 content_snapshot + html_snapshot（约 90MB）撑爆堆内存。
     * 一次性迁移（部署 Java 并执行 DDL 后调用一次）；幂等，可重复执行。
     *
     * @return 回填页面数
     */
    @Override
    public int backfillMediaSizes() {
        List<Long> ids = repoPageRepository.listIdsForMediaBackfill();
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int updated = 0;
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            try {
                RepoPageDomain page = repoPageRepository.findById(id);
                if (page == null) {
                    continue;
                }
                long size = computeMediaSize(id, page.getContentSnapshot(), page.getHtmlSnapshot(),
                        page.getOriginalFileKey());
                if (repoPageRepository.updateMediaSize(id, size)) {
                    updated++;
                }
            } catch (Exception e) {
                log.warn("repo page media_size backfill failed, pageId={}", id, e);
            }
        }
        log.info("repo page media_size backfilled: total={}, updated={}", ids.size(), updated);
        return updated;
    }

    /**
     * 组装树：按 parentId 分组，组内按 position 排序。
     */
    private List<RepoPageTreeNode> buildTree(List<RepoPageDomain> all) {
        Map<Long, List<RepoPageDomain>> byParent = new HashMap<>();
        Map<Long, RepoPageTreeNode> nodeById = new HashMap<>();
        for (RepoPageDomain page : all) {
            byParent.computeIfAbsent(page.getParentId(), k -> new ArrayList<>()).add(page);
        }
        for (RepoPageDomain page : all) {
            RepoPageTreeNode node = RepoPageTreeNode.builder().page(page).children(new ArrayList<>()).build();
            nodeById.put(page.getId(), node);
        }
        List<RepoPageTreeNode> roots = new ArrayList<>();
        Comparator<RepoPageTreeNode> byPosition = Comparator.comparing(n -> n.getPage().getPosition());
        for (RepoPageDomain page : all) {
            RepoPageTreeNode node = nodeById.get(page.getId());
            List<RepoPageDomain> children = byParent.get(page.getId());
            if (children != null) {
                for (RepoPageDomain child : children) {
                    node.getChildren().add(nodeById.get(child.getId()));
                }
                node.getChildren().sort(byPosition);
            }
            if (page.getParentId() == null || !nodeById.containsKey(page.getParentId())) {
                roots.add(node);
            }
        }
        roots.sort(byPosition);
        return roots;
    }

    /**
     * 计算移动目标位置的排序键。
     * beforeId/afterId 均为空时追加到目标父节点末尾；否则取相邻两键之间的中点。
     */
    private String resolveMovePosition(RepoPageDomain page, Long targetParentId, Long beforeId, Long afterId) {
        if (beforeId == null && afterId == null) {
            List<RepoPageDomain> siblings = listSiblingsExcluding(page.getSpaceId(), targetParentId, page.getId());
            return siblings.isEmpty()
                    ? PositionUtils.between(null, null)
                    : PositionUtils.between(siblings.get(siblings.size() - 1).getPosition(), null);
        }

        String beforePosition = null;
        String afterPosition = null;
        if (beforeId != null) {
            beforePosition = positionOfSibling(page, targetParentId, beforeId);
        }
        if (afterId != null) {
            afterPosition = positionOfSibling(page, targetParentId, afterId);
        }
        try {
            return PositionUtils.between(beforePosition, afterPosition);
        } catch (IllegalArgumentException e) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                    "invalid move position: " + e.getMessage());
        }
    }

    /**
     * 校验锚点页面是目标父节点下的有效兄弟节点，并返回其排序键。
     */
    private String positionOfSibling(RepoPageDomain page, Long targetParentId, Long anchorId) {
        if (Objects.equals(anchorId, page.getId())) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                    "anchor cannot be the moving page itself");
        }
        RepoPageDomain anchor = repoPageRepository.findById(anchorId);
        if (anchor == null
                || !Objects.equals(anchor.getSpaceId(), page.getSpaceId())
                || !Objects.equals(anchor.getParentId(), targetParentId)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound);
        }
        return anchor.getPosition();
    }

    private List<RepoPageDomain> listSiblings(Long spaceId, Long parentId) {
        return parentId == null
                ? repoPageRepository.listRootsBySpaceId(spaceId)
                : repoPageRepository.listChildren(spaceId, parentId);
    }

    private List<RepoPageDomain> listSiblingsExcluding(Long spaceId, Long parentId, Long excludeId) {
        List<RepoPageDomain> siblings = listSiblings(spaceId, parentId);
        List<RepoPageDomain> result = new ArrayList<>(siblings.size());
        for (RepoPageDomain sibling : siblings) {
            if (!Objects.equals(sibling.getId(), excludeId)) {
                result.add(sibling);
            }
        }
        return result;
    }

    /**
     * 收集页面及其全部子孙页面ID（广度优先）。
     */
    private List<Long> collectSubtreeIds(List<RepoPageDomain> all, Long rootId) {
        Map<Long, List<Long>> childrenMap = new HashMap<>();
        for (RepoPageDomain page : all) {
            if (page.getParentId() != null) {
                childrenMap.computeIfAbsent(page.getParentId(), k -> new ArrayList<>()).add(page.getId());
            }
        }
        List<Long> result = new ArrayList<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.push(rootId);
        Set<Long> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            Long current = queue.pop();
            if (!visited.add(current)) {
                continue;
            }
            result.add(current);
            List<Long> children = childrenMap.get(current);
            if (children != null) {
                queue.addAll(children);
            }
        }
        return result;
    }

    /**
     * 维护历史编辑者列表：追加当前用户（已在列表中保持原位置），超出上限裁掉最早的。
     */
    private List<Long> appendEditorId(List<Long> existing, Long userId) {
        List<Long> editorIds = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
        if (userId != null && !editorIds.contains(userId)) {
            editorIds.add(userId);
            while (editorIds.size() > RepoConstants.EDITOR_IDS_MAX) {
                editorIds.remove(0);
            }
        }
        return editorIds;
    }

    /**
     * 归一化导入源扩展名：去空白、转小写、去前导点；空返回 null；超长拒绝。
     */
    private String normalizeSourceExt(String sourceExt) {
        if (sourceExt == null) {
            return null;
        }
        String trimmed = sourceExt.trim();
        while (trimmed.startsWith(".")) {
            trimmed = trimmed.substring(1);
        }
        trimmed = trimmed.toLowerCase();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > SOURCE_EXT_MAX_LENGTH) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                    "sourceExt too long, max " + SOURCE_EXT_MAX_LENGTH);
        }
        return trimmed;
    }

    private RepoPageDomain mustGetPage(Long pageId) {
        RepoPageDomain page = repoPageRepository.findById(pageId);
        if (page == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound);
        }
        return page;
    }

    /**
     * 归一化页面类型：null/空回落 doc；非法值拒绝。
     */
    private String normalizePageType(String pageType) {
        String trimmed = pageType == null ? "" : pageType.trim();
        if (trimmed.isEmpty()) {
            return RepoPageTypeEnum.DOC.getCode();
        }
        if (!RepoPageTypeEnum.isValid(trimmed)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                    "invalid pageType: " + trimmed);
        }
        return trimmed;
    }

    private String normalizeTitle(String title) {
        String trimmed = title == null ? "" : title.trim();
        if (trimmed.length() > TITLE_MAX_LENGTH) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                    "title too long, max " + TITLE_MAX_LENGTH);
        }
        return trimmed;
    }

    /**
     * 生成短链ID（16 位，字符集去掉易混淆字符）
     */
    private String generateSlugId() {
        char[] buf = new char[RepoConstants.SLUG_ID_LENGTH];
        for (int i = 0; i < buf.length; i++) {
            buf[i] = SLUG_ALPHABET[RANDOM.nextInt(SLUG_ALPHABET.length)];
        }
        return new String(buf);
    }
}
