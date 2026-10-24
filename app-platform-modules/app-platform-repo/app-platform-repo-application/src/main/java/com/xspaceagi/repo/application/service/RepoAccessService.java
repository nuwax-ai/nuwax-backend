package com.xspaceagi.repo.application.service;

import com.xspaceagi.repo.application.service.dto.RepoAccessResult;
import com.xspaceagi.repo.domain.model.RepoPageAclDomain;
import com.xspaceagi.repo.domain.model.RepoPageDomain;
import com.xspaceagi.repo.domain.model.RepoShareDomain;
import com.xspaceagi.repo.domain.model.RepoShareLinkDomain;
import com.xspaceagi.repo.domain.model.RepoShareUserDomain;
import com.xspaceagi.repo.domain.repository.RepoPageAclRepository;
import com.xspaceagi.repo.domain.repository.RepoPageRepository;
import com.xspaceagi.repo.domain.repository.RepoShareLinkRepository;
import com.xspaceagi.repo.domain.repository.RepoShareRepository;
import com.xspaceagi.repo.domain.repository.RepoShareUserRepository;
import com.xspaceagi.repo.spec.constants.RepoConstants;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.SpacePermissionException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 文档统一访问裁决服务（需求4分享 + 需求7按人权限共用）。
 * <p>
 * 裁决优先级与语义（与计划一致）：
 * <ol>
 *   <li>匿名（无登录态）：仅当持有该页面的 public 分享链接 token → 只读；</li>
 *   <li>源空间 Owner/Admin：全权限（含删除/管权限），不受文档级权限开关影响；</li>
 *   <li>页面创建者：查看/编辑/分享/导出 + 删除 + 管权限，不受开关影响；</li>
 *   <li>源空间普通成员：开关关闭=全权限（现状）；开关开启=按 ACL 条目，无条目降为仅查看；</li>
 *   <li>非源空间成员：命中分享记录（目标空间在用户空间列表内）的权限位并集；
 *       或命中分享给个人的记录（需求3）的权限位并集；</li>
 *   <li>登录用户持有该页面有效链接：补充查看权限（链接不提升编辑权限）。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class RepoAccessService {

    private final SpacePermissionService spacePermissionService;

    private final RepoShareRepository repoShareRepository;

    private final RepoShareUserRepository repoShareUserRepository;

    private final RepoShareLinkRepository repoShareLinkRepository;

    private final RepoPageAclRepository repoPageAclRepository;

    private final RepoPageRepository repoPageRepository;

    /**
     * 裁决访问权限。
     *
     * @param page       页面（必须已按租户查出）
     * @param userId     当前用户，null=匿名
     * @param shareToken 分享链接 token，可空
     */
    public RepoAccessResult resolveAccess(RepoPageDomain page, Long userId, String shareToken) {
        if (userId == null) {
            // 匿名：仅 public 链接放行；按链接权限位放行只读类（导出/复制），
            // 评论需同时含评论位与 allow_comment=1；编辑位永不生效
            RepoShareLinkDomain anonLink = shareToken == null ? null
                    : findValidShareLink(page.getId(), shareToken, true);
            if (anonLink != null) {
                int linkBits = anonLink.getPermBits() == null ? RepoConstants.PERM_VIEW : anonLink.getPermBits();
                linkBits &= ~RepoConstants.PERM_EDIT;
                boolean anonCommentable = (linkBits & RepoConstants.PERM_COMMENT) != 0
                        && anonLink.getAllowComment() != null && anonLink.getAllowComment() == 1;
                return RepoAccessResult.builder()
                        .viewable(true)
                        .exportable((linkBits & RepoConstants.PERM_EXPORT) != 0)
                        .copyable((linkBits & RepoConstants.PERM_COPY) != 0)
                        .commentable(anonCommentable)
                        .via("link")
                        .permBits(linkBits)
                        .build();
            }
            return none();
        }

        int bits = 0;
        boolean deletable = false;
        boolean manageable = false;
        boolean commentable = false;
        String via = "none";

        // 创建者：全权限 + 删除 + 管理，不受开关影响
        boolean creator = page.getCreatorId() != null && Objects.equals(page.getCreatorId(), userId);
        if (creator) {
            bits |= RepoConstants.PERM_FULL;
            deletable = true;
            manageable = true;
            via = "creator";
        }

        // 源空间角色
        String role = spacePermissionService.getSpaceUserRole(page.getSpaceId(), userId);
        if (RepoConstants.ROLE_OWNER.equals(role) || RepoConstants.ROLE_ADMIN.equals(role)) {
            bits |= RepoConstants.PERM_FULL;
            deletable = true;
            manageable = true;
            via = "space-admin";
        } else if (RepoConstants.ROLE_USER.equals(role)) {
            if (page.getAclEnabled() != null && page.getAclEnabled() == 1) {
                // 开启按人权限：有条目按条目，无条目降为仅查看
                RepoPageAclDomain acl = repoPageAclRepository.findByPageIdAndUserId(page.getId(), userId);
                bits |= RepoConstants.PERM_VIEW;
                if (acl != null && acl.getPermBits() != null) {
                    bits |= acl.getPermBits();
                }
                if (!creator) {
                    via = "acl";
                }
            } else {
                bits |= RepoConstants.PERM_FULL;
                if (!creator) {
                    via = "space-member";
                }
            }
        } else {
            // 非源空间成员：被分享空间命中则取权限位并集
            List<Long> mySpaceIds = spacePermissionService.querySpaceIdList(userId);
            if (mySpaceIds != null && !mySpaceIds.isEmpty()) {
                List<RepoShareDomain> shares = repoShareRepository.listByPageIdAndTargetSpaceIds(page.getId(), mySpaceIds);
                for (RepoShareDomain share : shares) {
                    if (share.getPermBits() != null) {
                        bits |= share.getPermBits();
                    }
                    via = "shared";
                }
            }
            // 需求3：分享给个人命中则并入权限位
            List<RepoShareUserDomain> userShares = repoShareUserRepository.listByPageIdAndTargetUserId(page.getId(), userId);
            for (RepoShareUserDomain share : userShares) {
                if (share.getPermBits() != null) {
                    bits |= share.getPermBits();
                }
                via = "shared-user";
            }
        }

        // 登录用户持有效链接（任意类型）：并入链接权限位（存量无权限位链接按仅查看处理；
        // 仅公开链接（恒只读）去编辑位，登录可访问链接允许编辑位生效）
        RepoShareLinkDomain validLink = shareToken == null ? null
                : findValidShareLink(page.getId(), shareToken, false);
        if (validLink != null) {
            int linkBits = validLink.getPermBits() == null ? RepoConstants.PERM_VIEW : validLink.getPermBits();
            if (RepoConstants.SHARE_LINK_PUBLIC.equals(validLink.getAccessType())) {
                linkBits &= ~RepoConstants.PERM_EDIT;
            }
            if ((bits & RepoConstants.PERM_VIEW) == 0 && (linkBits & RepoConstants.PERM_VIEW) != 0) {
                via = "link";
            }
            bits |= linkBits;
        }

        // 评论位：空间分享/个人分享/按人权限/链接权限位（含 PERM_COMMENT 即可评论）
        commentable = (bits & RepoConstants.PERM_COMMENT) != 0;
        // 链接 allow_comment=1 另行授予评论（兼容存量无评论位的链接）
        if (validLink != null && validLink.getAllowComment() != null && validLink.getAllowComment() == 1) {
            commentable = true;
        }

        boolean viewable = (bits & RepoConstants.PERM_VIEW) != 0;
        return RepoAccessResult.builder()
                .viewable(viewable)
                .editable((bits & RepoConstants.PERM_EDIT) != 0)
                .exportable((bits & RepoConstants.PERM_EXPORT) != 0)
                .copyable((bits & RepoConstants.PERM_COPY) != 0)
                .shareable((bits & RepoConstants.PERM_SHARE) != 0)
                .deletable(deletable)
                .manageable(manageable)
                .commentable(viewable && commentable)
                .via(viewable ? via : "none")
                .permBits(viewable ? bits : 0)
                .build();
    }

    /**
     * 裁决当前登录用户对页面的访问（无链接上下文）。
     */
    public RepoAccessResult resolveAccess(RepoPageDomain page) {
        return resolveAccess(page, null);
    }

    /**
     * 裁决当前登录用户对页面的访问（携带分享链接 token，本轮需求4）。
     */
    public RepoAccessResult resolveAccess(RepoPageDomain page, String shareToken) {
        return resolveAccess(page, RequestContext.get() == null ? null : RequestContext.get().getUserId(), shareToken);
    }

    /**
     * 要求可查看，否则抛 {@link SpacePermissionException}（全局异常处理转 403）。
     */
    public RepoAccessResult requireViewable(RepoPageDomain page) {
        return requireViewable(page, null);
    }

    /**
     * 要求可查看（携带分享链接 token 裁决，本轮需求4：链接授予的只读权限位）。
     */
    public RepoAccessResult requireViewable(RepoPageDomain page, String shareToken) {
        Long userId = RequestContext.get() == null ? null : RequestContext.get().getUserId();
        RepoAccessResult access = resolveAccess(page, userId, shareToken);
        if (!access.isViewable()) {
            throw new SpacePermissionException();
        }
        return access;
    }

    /**
     * 要求可编辑（快照回写/重命名/移动/子页创建/版本恢复等写操作）。
     */
    public RepoAccessResult requireEditable(RepoPageDomain page) {
        RepoAccessResult access = resolveAccess(page);
        if (!access.isEditable()) {
            throw new SpacePermissionException();
        }
        return access;
    }

    /**
     * 要求可评论（需求5）：严格按 commentable（评论权限位 / 分享链接 allow_comment）裁决，
     * 与可编辑解耦——仅授予编辑位、未勾选「可评论」的用户不可评论。
     */
    public RepoAccessResult requireCommentable(RepoPageDomain page) {
        RepoAccessResult access = resolveAccess(page);
        if (!access.isCommentable()) {
            throw new SpacePermissionException();
        }
        return access;
    }

    /**
     * 要求可导出。
     */
    public RepoAccessResult requireExportable(RepoPageDomain page) {
        return requireExportable(page, null);
    }

    /**
     * 要求可导出（携带分享链接 token 裁决，本轮需求4：链接授予导出位时可用）。
     */
    public RepoAccessResult requireExportable(RepoPageDomain page, String shareToken) {
        Long userId = RequestContext.get() == null ? null : RequestContext.get().getUserId();
        RepoAccessResult access = resolveAccess(page, userId, shareToken);
        if (!access.isExportable()) {
            throw new SpacePermissionException();
        }
        return access;
    }

    /**
     * 要求可复制（创建副本）。
     */
    public RepoAccessResult requireCopyable(RepoPageDomain page) {
        return requireCopyable(page, null);
    }

    /**
     * 要求可复制（携带分享链接 token 裁决，本轮需求4：链接授予复制位时可用）。
     */
    public RepoAccessResult requireCopyable(RepoPageDomain page, String shareToken) {
        Long userId = RequestContext.get() == null ? null : RequestContext.get().getUserId();
        RepoAccessResult access = resolveAccess(page, userId, shareToken);
        if (!access.isCopyable()) {
            throw new SpacePermissionException();
        }
        return access;
    }

    /**
     * 要求可分享或可管理（发起分享/管理分享记录）。
     */
    public RepoAccessResult requireShareable(RepoPageDomain page) {
        RepoAccessResult access = resolveAccess(page);
        if (!access.isShareable() && !access.isManageable()) {
            throw new SpacePermissionException();
        }
        return access;
    }

    /**
     * 要求可管理（设置文档级按人权限、删除分享记录）。
     */
    public RepoAccessResult requireManageable(RepoPageDomain page) {
        RepoAccessResult access = resolveAccess(page);
        if (!access.isManageable()) {
            throw new SpacePermissionException();
        }
        return access;
    }

    /**
     * 要求可删除（源空间管理员/属主或创建者）。
     */
    public RepoAccessResult requireDeletable(RepoPageDomain page) {
        RepoAccessResult access = resolveAccess(page);
        if (!access.isDeletable()) {
            throw new SpacePermissionException();
        }
        return access;
    }

    /**
     * 按 ID 取页面并裁决查看权限（读路径复用）：页面不存在返回 null，无权限抛异常。
     */
    public RepoPageDomain getViewablePage(Long pageId) {
        RepoPageDomain page = repoPageRepository.findById(pageId);
        if (page == null) {
            return null;
        }
        requireViewable(page);
        return page;
    }

    /**
     * 查询当前用户对页面的访问状况（前端权限同步用；页面不存在抛资源不存在异常，
     * 无权限不抛异常、返回 viewable=false 的结果）。
     */
    public RepoAccessResult getMyAccess(Long pageId) {
        return getMyAccess(pageId, null);
    }

    /**
     * 查询当前用户对页面的访问状况（携带分享链接 token 裁决）。
     */
    public RepoAccessResult getMyAccess(Long pageId, String shareToken) {
        RepoPageDomain page = repoPageRepository.findById(pageId);
        if (page == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound);
        }
        Long userId = RequestContext.get() == null ? null : RequestContext.get().getUserId();
        return resolveAccess(page, userId, shareToken);
    }

    /**
     * 分享链接 token 校验。
     *
     * @param pageId        页面ID
     * @param token         链接 token
     * @param requirePublic true=仅 public（任何人）链接有效（匿名场景）
     */
    public boolean isShareLinkValid(Long pageId, String token, boolean requirePublic) {
        return findValidShareLink(pageId, token, requirePublic) != null;
    }

    /**
     * 按 token 取该页面的有效分享链接；无效/不属于该页/类型不符返回 null。
     *
     * @param requirePublic true=仅 public（任何人）链接有效（匿名场景）
     */
    public RepoShareLinkDomain findValidShareLink(Long pageId, String token, boolean requirePublic) {
        if (token == null || token.isBlank()) {
            return null;
        }
        RepoShareLinkDomain link = repoShareLinkRepository.findByToken(token);
        if (link == null || !Objects.equals(link.getPageId(), pageId)) {
            return null;
        }
        if (requirePublic && !RepoConstants.SHARE_LINK_PUBLIC.equals(link.getAccessType())) {
            return null;
        }
        return link;
    }

    /**
     * 分享链接进入自动加入「与我共享」：登录用户通过有效分享链接打开文档时，若其查看权限
     * 确由链接授予（via=link，即非空间成员、也无其它空间/个人分享），则把该链接来源记成一条
     * 个人分享记录（repo_share_user），使文档出现在该用户「与我共享」列表（相当于点链接即被分享）。
     * <p>
     * 幂等：已有有效记录仅覆盖权限位、软删记录复活，反复进入不会重复插入。链接来源只授予
     * 查看权限位（不把链接的编辑/导出等位持久化，避免链接撤销后权限留存）。
     */
    public void recordShareLinkAccess(RepoPageDomain page, String shareToken) {
        RequestContext ctx = RequestContext.get();
        if (ctx == null || ctx.getUserId() == null || page == null || shareToken == null || shareToken.isBlank()) {
            return;
        }
        Long userId = ctx.getUserId();
        RepoShareLinkDomain link = findValidShareLink(page.getId(), shareToken, false);
        if (link == null) {
            return;
        }
        // 仅当查看权限确实来自链接（链接是唯一访问来源）才记录，避免空间成员/已有分享者重复出现
        if (!"link".equals(resolveAccess(page, userId, shareToken).getVia())) {
            return;
        }
        int bits = RepoConstants.PERM_VIEW;
        Long createdBy = link.getCreatedBy();
        // 记录来源链接，删除分享链接时按 link_id 级联清理这条「与我共享」记录
        Long linkId = link.getId();
        RepoShareUserDomain existing = repoShareUserRepository.findByPageIdAndTargetUserIdIncludeDeleted(page.getId(), userId);
        if (existing == null) {
            repoShareUserRepository.insert(RepoShareUserDomain.builder()
                    .tenantId(ctx.getTenantId())
                    .pageId(page.getId())
                    .targetUserId(userId)
                    .permBits(bits)
                    .createdBy(createdBy)
                    .linkId(linkId)
                    .build());
        } else if (existing.getYn() != null && existing.getYn() == RepoConstants.YN_VALID) {
            repoShareUserRepository.updatePermBits(existing.getId(), bits, linkId);
        } else {
            repoShareUserRepository.revive(existing.getId(), bits, createdBy, linkId);
        }
    }

    private RepoAccessResult viewOnly(String via) {
        return RepoAccessResult.builder()
                .viewable(true)
                .via(via)
                .build();
    }

    private RepoAccessResult none() {
        return RepoAccessResult.builder().via("none").build();
    }
}
