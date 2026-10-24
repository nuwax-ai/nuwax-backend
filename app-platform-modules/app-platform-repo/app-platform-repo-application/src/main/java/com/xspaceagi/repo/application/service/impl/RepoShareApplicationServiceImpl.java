package com.xspaceagi.repo.application.service.impl;

import com.xspaceagi.repo.application.service.RepoAccessService;
import com.xspaceagi.repo.application.service.RepoShareApplicationService;
import com.xspaceagi.repo.application.service.dto.RepoAccessResult;
import com.xspaceagi.repo.application.service.dto.RepoAclStatus;
import com.xspaceagi.repo.application.service.dto.RepoShareLinkStatsVO;
import com.xspaceagi.repo.application.service.dto.RepoShareLinkVisitUserVO;
import com.xspaceagi.repo.application.service.dto.RepoShareSpaceVO;
import com.xspaceagi.repo.application.service.dto.RepoShareStatus;
import com.xspaceagi.repo.application.service.dto.RepoShareUserVO;
import com.xspaceagi.repo.application.service.dto.RepoSpaceShareItemVO;
import com.xspaceagi.repo.domain.model.RepoPageAclDomain;
import com.xspaceagi.repo.domain.model.RepoPageDomain;
import com.xspaceagi.repo.domain.model.RepoShareDomain;
import com.xspaceagi.repo.domain.model.RepoShareLinkDomain;
import com.xspaceagi.repo.domain.model.RepoShareLinkVisitDomain;
import com.xspaceagi.repo.domain.model.RepoShareUserDomain;
import com.xspaceagi.repo.domain.repository.RepoPageAclRepository;
import com.xspaceagi.repo.domain.repository.RepoPageRepository;
import com.xspaceagi.repo.domain.repository.RepoPageVisitRepository;
import com.xspaceagi.repo.domain.repository.RepoShareLinkRepository;
import com.xspaceagi.repo.domain.repository.RepoShareLinkVisitRepository;
import com.xspaceagi.repo.domain.repository.RepoShareRepository;
import com.xspaceagi.repo.domain.repository.RepoShareUserRepository;
import com.xspaceagi.repo.spec.constants.RepoConstants;
import com.xspaceagi.system.application.dto.SendNotifyMessageDto;
import com.xspaceagi.system.application.dto.SpaceDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.NotifyMessageApplicationService;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.infra.dao.entity.NotifyMessage;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.SpacePermissionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 文档分享与按人权限应用服务实现（需求4/需求7）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepoShareApplicationServiceImpl implements RepoShareApplicationService {

    /**
     * 分享链接 token 字符集（去掉易混淆的 0/O/1/l/I）
     */
    private static final char[] TOKEN_ALPHABET =
            "23456789abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final int TOKEN_MAX_RETRY = 5;

    private final RepoPageRepository repoPageRepository;

    private final RepoShareRepository repoShareRepository;

    private final RepoShareUserRepository repoShareUserRepository;

    private final RepoShareLinkRepository repoShareLinkRepository;

    private final RepoShareLinkVisitRepository repoShareLinkVisitRepository;

    private final RepoPageVisitRepository repoPageVisitRepository;

    private final RepoPageAclRepository repoPageAclRepository;

    private final RepoAccessService repoAccessService;

    private final SpacePermissionService spacePermissionService;

    private final UserApplicationService userApplicationService;

    private final SpaceApplicationService spaceApplicationService;

    private final NotifyMessageApplicationService notifyMessageApplicationService;

    @Override
    public RepoShareStatus getShareStatus(Long pageId) {
        RepoPageDomain page = mustGetPage(pageId);
        RepoAccessResult access = repoAccessService.requireViewable(page);
        List<RepoShareUserDomain> userShares = repoShareUserRepository.listManualByPageId(pageId);
        Set<Long> targetUserIds = new HashSet<>();
        for (RepoShareUserDomain share : userShares) {
            if (share.getTargetUserId() != null) {
                targetUserIds.add(share.getTargetUserId());
            }
        }
        Map<Long, String> userNames = loadUserNames(targetUserIds);
        List<RepoShareUserVO> userShareVOs = new ArrayList<>();
        for (RepoShareUserDomain share : userShares) {
            userShareVOs.add(RepoShareUserVO.from(share, userNames.get(share.getTargetUserId())));
        }
        List<RepoShareDomain> spaceShares = repoShareRepository.listByPageId(pageId);
        return RepoShareStatus.builder()
                .spaceShares(toSpaceShareVOs(spaceShares))
                .userShares(userShareVOs)
                .links(repoShareLinkRepository.listByPageId(pageId))
                .manageable(access.isManageable())
                .shareable(access.isShareable() || access.isManageable())
                .myPermBits(access.getPermBits())
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void shareToSpaces(Long pageId, List<Long> targetSpaceIds, Integer permBits) {
        RepoPageDomain page = mustGetPage(pageId);
        RepoAccessResult access = repoAccessService.requireShareable(page);
        int bits = clampToHeld(normalizePermBits(permBits), access);
        if (targetSpaceIds == null || targetSpaceIds.isEmpty()) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty);
        }

        Long userId = RequestContext.get().getUserId();
        Long tenantId = RequestContext.get().getTenantId();
        // 目标空间必须是操作者自己所在空间（防猜ID分享到任意空间）
        List<Long> mySpaceIds = spacePermissionService.querySpaceIdList(userId);
        Set<Long> mySpaceSet = mySpaceIds == null ? Set.of() : new HashSet<>(mySpaceIds);
        for (Long targetSpaceId : targetSpaceIds) {
            if (targetSpaceId == null || !mySpaceSet.contains(targetSpaceId)) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                        "target space not accessible: " + targetSpaceId);
            }
            upsertShare(page, targetSpaceId, bits, userId, tenantId);
        }
        log.info("repo page shared to spaces: pageId={}, targetSpaceIds={}, permBits={}, userId={}",
                pageId, targetSpaceIds, bits, userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void shareToUsers(Long pageId, List<Long> targetUserIds, Integer permBits) {
        RepoPageDomain page = mustGetPage(pageId);
        RepoAccessResult access = repoAccessService.requireShareable(page);
        int bits = clampToHeld(normalizePermBits(permBits), access);
        if (targetUserIds == null || targetUserIds.isEmpty()) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty);
        }

        Long userId = RequestContext.get().getUserId();
        Long tenantId = RequestContext.get().getTenantId();
        // 去重 + 排除自己；目标用户必须真实存在
        Set<Long> targetSet = new HashSet<>();
        for (Long targetUserId : targetUserIds) {
            if (targetUserId == null || Objects.equals(targetUserId, userId)) {
                continue;
            }
            targetSet.add(targetUserId);
        }
        if (targetSet.isEmpty()) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty);
        }
        List<UserDto> users = userApplicationService.queryUserListByIds(new ArrayList<>(targetSet));
        Set<Long> existingIds = new HashSet<>();
        if (users != null) {
            for (UserDto user : users) {
                existingIds.add(user.getId());
            }
        }
        for (Long targetUserId : targetSet) {
            if (!existingIds.contains(targetUserId)) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                        "target user not found: " + targetUserId);
            }
            upsertShareUser(page, targetUserId, bits, userId, tenantId);
        }
        log.info("repo page shared to users: pageId={}, targetUserIds={}, permBits={}, userId={}",
                pageId, targetSet, bits, userId);
        sendShareNotifications(tenantId, userId, page, new ArrayList<>(targetSet));
    }

    /**
     * @ 提及默认授权（见接口注释）：非空间成员补个人分享 VIEW|COMMENT，空间成员跳过。
     * <p>
     * 只增不减：已有记录（可能是 HTTPS 分享给个人 / 链接自动加入）按位并集，避免「@ 一下」
     * 把对方的编辑权限抹成只读——这类降级用户无法察觉，只会觉得「突然不能编辑了」。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int grantMentionAccess(Long pageId, List<Long> targetUserIds) {
        if (targetUserIds == null || targetUserIds.isEmpty()) {
            return 0;
        }
        RepoPageDomain page = mustGetPage(pageId);
        RepoAccessResult access = repoAccessService.requireViewable(page);
        if (!access.isEditable() && !access.isCommentable()) {
            // 只读访客不能借 @ 给别人开权限
            throw new SpacePermissionException();
        }

        Long operatorId = RequestContext.get().getUserId();
        Long tenantId = RequestContext.get().getTenantId();
        Set<Long> targets = new HashSet<>();
        for (Long targetUserId : targetUserIds) {
            if (targetUserId != null && !Objects.equals(targetUserId, operatorId)) {
                targets.add(targetUserId);
            }
        }
        if (targets.isEmpty()) {
            return 0;
        }
        // 目标用户必须真实存在（防伪造 id 往分享表里写脏数据）
        Set<Long> existingIds = new HashSet<>();
        List<UserDto> users = userApplicationService.queryUserListByIds(new ArrayList<>(targets));
        if (users != null) {
            for (UserDto user : users) {
                if (user.getId() != null) {
                    existingIds.add(user.getId());
                }
            }
        }

        int granted = 0;
        for (Long targetUserId : targets) {
            if (!existingIds.contains(targetUserId)) {
                continue;
            }
            // 文档所在空间的成员：本来就能看，不再额外授权。
            // 这里不能用 getSpaceUserRole：它会把「平台管理员」兜底成 Admin，而管理员通常并不是
            // 该空间的成员——跳过他们，文档就永远进不了他们的「与我共享」，被 @ 了也找不到。
            if (spaceApplicationService.querySpaceUser(page.getSpaceId(), targetUserId) != null) {
                continue;
            }
            RepoShareUserDomain existing =
                    repoShareUserRepository.findByPageIdAndTargetUserIdIncludeDeleted(page.getId(), targetUserId);
            int bits = RepoConstants.PERM_VIEW | RepoConstants.PERM_COMMENT;
            if (existing != null && existing.getYn() != null && existing.getYn() == RepoConstants.YN_VALID
                    && existing.getPermBits() != null) {
                bits |= existing.getPermBits();
            }
            upsertShareUser(page, targetUserId, bits, operatorId, tenantId);
            granted++;
        }
        log.info("repo page mention access granted: pageId={}, targetUserIds={}, granted={}, operatorId={}",
                pageId, targets, granted, operatorId);
        return granted;
    }

    @Override
    public List<RepoSpaceShareItemVO> listSpaceReceivedShares(Long spaceId) {
        spacePermissionService.checkSpaceUserPermission(spaceId);
        List<RepoShareDomain> shares = repoShareRepository.listByTargetSpaceIds(List.of(spaceId));
        if (shares.isEmpty()) {
            return new ArrayList<>();
        }
        Set<Long> pageIdSet = new HashSet<>();
        for (RepoShareDomain share : shares) {
            pageIdSet.add(share.getPageId());
        }
        // 批量取页面并逐页裁决可查看（无权限静默过滤）
        Map<Long, RepoPageDomain> pageById = new HashMap<>();
        for (RepoPageDomain page : repoPageRepository.listByIds(new ArrayList<>(pageIdSet))) {
            if (isViewableQuietly(page)) {
                pageById.put(page.getId(), page);
            }
        }
        if (pageById.isEmpty()) {
            return new ArrayList<>();
        }
        Set<Long> sharerIds = new HashSet<>();
        Set<Long> sourceSpaceIds = new HashSet<>();
        for (RepoShareDomain share : shares) {
            RepoPageDomain page = pageById.get(share.getPageId());
            if (page == null) {
                continue;
            }
            if (share.getCreatedBy() != null) {
                sharerIds.add(share.getCreatedBy());
            }
            if (page.getSpaceId() != null) {
                sourceSpaceIds.add(page.getSpaceId());
            }
        }
        Map<Long, String> sharerNames = loadUserNames(sharerIds);
        Map<Long, String> spaceNames = loadSpaceNames(sourceSpaceIds);

        List<RepoSpaceShareItemVO> result = new ArrayList<>();
        for (RepoShareDomain share : shares) {
            RepoPageDomain page = pageById.get(share.getPageId());
            if (page == null) {
                continue;
            }
            result.add(RepoSpaceShareItemVO.builder()
                    .id(page.getId())
                    .shareId(share.getId())
                    .slugId(page.getSlugId())
                    .title(page.getTitle())
                    .pageType(page.getPageType())
                    .sourceExt(page.getSourceExt())
                    .sourceSpaceId(page.getSpaceId())
                    .sourceSpaceName(spaceNames.get(page.getSpaceId()))
                    .sharedByName(sharerNames.get(share.getCreatedBy()))
                    .permBits(share.getPermBits())
                    .sharedAt(share.getCreated())
                    .build());
        }
        return result;
    }

    @Override
    public List<RepoSpaceShareItemVO> listSpaceSentShares(Long spaceId) {
        spacePermissionService.checkSpaceUserPermission(spaceId);
        List<RepoPageDomain> pages = repoPageRepository.listBySpaceId(spaceId);
        if (pages.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Long, RepoPageDomain> pageById = new HashMap<>();
        List<Long> pageIds = new ArrayList<>();
        for (RepoPageDomain page : pages) {
            pageIds.add(page.getId());
            pageById.put(page.getId(), page);
        }
        List<RepoShareDomain> shares = repoShareRepository.listByPageIds(pageIds);
        if (shares.isEmpty()) {
            return new ArrayList<>();
        }
        Set<Long> sharerIds = new HashSet<>();
        Set<Long> targetSpaceIds = new HashSet<>();
        for (RepoShareDomain share : shares) {
            if (share.getCreatedBy() != null) {
                sharerIds.add(share.getCreatedBy());
            }
            if (share.getTargetSpaceId() != null) {
                targetSpaceIds.add(share.getTargetSpaceId());
            }
        }
        Map<Long, String> sharerNames = loadUserNames(sharerIds);
        Map<Long, String> spaceNames = loadSpaceNames(targetSpaceIds);

        List<RepoSpaceShareItemVO> result = new ArrayList<>();
        for (RepoShareDomain share : shares) {
            RepoPageDomain page = pageById.get(share.getPageId());
            if (page == null || !isViewableQuietly(page)) {
                continue;
            }
            result.add(RepoSpaceShareItemVO.builder()
                    .id(page.getId())
                    .shareId(share.getId())
                    .slugId(page.getSlugId())
                    .title(page.getTitle())
                    .pageType(page.getPageType())
                    .sourceExt(page.getSourceExt())
                    .targetSpaceId(share.getTargetSpaceId())
                    .targetSpaceName(spaceNames.get(share.getTargetSpaceId()))
                    .sharedByName(sharerNames.get(share.getCreatedBy()))
                    .permBits(share.getPermBits())
                    .sharedAt(share.getCreated())
                    .build());
        }
        return result;
    }

    @Override
    public void unshare(Long pageId, Long shareId) {
        RepoPageDomain page = mustGetPage(pageId);
        RepoAccessResult access = repoAccessService.resolveAccess(page);
        if (!access.isViewable()) {
            throw new SpacePermissionException();
        }
        RepoShareDomain share = repoShareRepository.listByPageId(pageId).stream()
                .filter(s -> Objects.equals(s.getId(), shareId))
                .findFirst()
                .orElseThrow(() -> BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound));
        Long userId = RequestContext.get().getUserId();
        if (!access.isManageable() && !Objects.equals(share.getCreatedBy(), userId)) {
            throw new SpacePermissionException();
        }
        repoShareRepository.softDelete(shareId);
        log.info("repo share removed: shareId={}, pageId={}, userId={}", shareId, pageId, userId);
    }

    @Override
    public void unshareUser(Long pageId, Long shareUserId) {
        RepoPageDomain page = mustGetPage(pageId);
        RepoAccessResult access = repoAccessService.resolveAccess(page);
        if (!access.isViewable()) {
            throw new SpacePermissionException();
        }
        RepoShareUserDomain share = repoShareUserRepository.listManualByPageId(pageId).stream()
                .filter(s -> Objects.equals(s.getId(), shareUserId))
                .findFirst()
                .orElseThrow(() -> BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound));
        Long userId = RequestContext.get().getUserId();
        if (!access.isManageable() && !Objects.equals(share.getCreatedBy(), userId)) {
            throw new SpacePermissionException();
        }
        repoShareUserRepository.softDelete(shareUserId);
        log.info("repo user share removed: shareUserId={}, pageId={}, userId={}", shareUserId, pageId, userId);
    }

    /**
     * 修改单条个人授权的权限位（授权管理里「编辑权限」）。
     * <p>
     * 与 {@link #shareToUsers} 分开：改权限不该再给被授权人发一遍「分享给了你」的站内通知。
     * 权限位同样裁剪到操作者自己持有的范围，且不允许通过改权限把授予范围放大。
     */
    @Override
    public void updateShareUserPerm(Long pageId, Long shareUserId, Integer permBits) {
        RepoPageDomain page = mustGetPage(pageId);
        RepoAccessResult access = repoAccessService.resolveAccess(page);
        if (!access.isViewable()) {
            throw new SpacePermissionException();
        }
        RepoShareUserDomain share = repoShareUserRepository.listManualByPageId(pageId).stream()
                .filter(s -> Objects.equals(s.getId(), shareUserId))
                .findFirst()
                .orElseThrow(() -> BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound));
        Long userId = RequestContext.get().getUserId();
        if (!access.isManageable() && !Objects.equals(share.getCreatedBy(), userId)) {
            throw new SpacePermissionException();
        }
        int bits = clampToHeld(normalizePermBits(permBits), access);
        repoShareUserRepository.updatePermBits(shareUserId, bits, share.getLinkId());
        log.info("repo user share perm updated: shareUserId={}, pageId={}, permBits={}, userId={}",
                shareUserId, pageId, bits, userId);
    }

    /**
     * 修改单条空间分享的权限位（授权管理里「编辑权限」）。权限位同样裁剪到操作者持有范围。
     */
    @Override
    public void updateSharePerm(Long pageId, Long shareId, Integer permBits) {
        RepoPageDomain page = mustGetPage(pageId);
        RepoAccessResult access = repoAccessService.resolveAccess(page);
        if (!access.isViewable()) {
            throw new SpacePermissionException();
        }
        RepoShareDomain share = repoShareRepository.listByPageId(pageId).stream()
                .filter(s -> Objects.equals(s.getId(), shareId))
                .findFirst()
                .orElseThrow(() -> BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound));
        Long userId = RequestContext.get().getUserId();
        if (!access.isManageable() && !Objects.equals(share.getCreatedBy(), userId)) {
            throw new SpacePermissionException();
        }
        int bits = clampToHeld(normalizePermBits(permBits), access);
        repoShareRepository.updatePermBits(shareId, bits);
        log.info("repo space share perm updated: shareId={}, pageId={}, permBits={}, userId={}",
                shareId, pageId, bits, userId);
    }

    @Override
    public void unshareAll(Long pageId) {
        RepoPageDomain page = mustGetPage(pageId);
        repoAccessService.requireManageable(page);
        int shares = repoShareRepository.softDeleteByPageId(pageId);
        int userShares = repoShareUserRepository.softDeleteByPageId(pageId);
        int links = repoShareLinkRepository.softDeleteByPageId(pageId);
        log.info("repo shares all removed: pageId={}, shares={}, userShares={}, links={}",
                pageId, shares, userShares, links);
    }

    @Override
    public RepoShareLinkDomain createShareLink(Long pageId, String accessType, Integer allowComment, Integer permBits) {
        RepoPageDomain page = mustGetPage(pageId);
        RepoAccessResult access = repoAccessService.requireShareable(page);
        if (!RepoConstants.SHARE_LINK_LOGIN.equals(accessType)
                && !RepoConstants.SHARE_LINK_PUBLIC.equals(accessType)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                    "accessType must be login or public");
        }
        int comment = allowComment != null && allowComment == 0 ? 0 : 1;
        // 需求：分享链接在「登录可访问」时可授予编辑权限——仅公开链接（匿名无身份可裁决，恒只读）强制去编辑位
        int bits = permBits == null ? RepoConstants.PERM_VIEW : normalizePermBits(permBits);
        if (RepoConstants.SHARE_LINK_PUBLIC.equals(accessType)) {
            bits &= ~RepoConstants.PERM_EDIT;
        }
        // 分享的权限不能超过自己已有的权限：裁剪权限位；自己无评论权限时不授予「允许评论」
        bits = clampToHeld(bits, access);
        if ((access.getPermBits() & RepoConstants.PERM_COMMENT) == 0) {
            comment = 0;
        }
        RepoShareLinkDomain domain = RepoShareLinkDomain.builder()
                .tenantId(RequestContext.get().getTenantId())
                .pageId(pageId)
                .token(generateUniqueToken())
                .accessType(accessType)
                .allowComment(comment)
                .permBits(bits)
                .createdBy(RequestContext.get().getUserId())
                .build();
        Long id = repoShareLinkRepository.insert(domain);
        domain.setId(id);
        log.info("repo share link created: id={}, pageId={}, accessType={}, allowComment={}, permBits={}, userId={}",
                id, pageId, accessType, comment, bits, domain.getCreatedBy());
        return domain;
    }

    @Override
    public void updateShareLinkComment(Long pageId, Long linkId, Integer allowComment) {
        RepoPageDomain page = mustGetPage(pageId);
        RepoAccessResult access = repoAccessService.resolveAccess(page);
        if (!access.isViewable()) {
            throw new SpacePermissionException();
        }
        RepoShareLinkDomain link = repoShareLinkRepository.listByPageId(pageId).stream()
                .filter(l -> Objects.equals(l.getId(), linkId))
                .findFirst()
                .orElseThrow(() -> BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound));
        Long userId = RequestContext.get().getUserId();
        if (!access.isManageable() && !Objects.equals(link.getCreatedBy(), userId)) {
            throw new SpacePermissionException();
        }
        int comment = allowComment != null && allowComment == 0 ? 0 : 1;
        repoShareLinkRepository.updateAllowComment(linkId, comment);
        log.info("repo share link allowComment updated: linkId={}, pageId={}, allowComment={}, userId={}",
                linkId, pageId, comment, userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteShareLink(Long pageId, Long linkId) {
        RepoPageDomain page = mustGetPage(pageId);
        RepoAccessResult access = repoAccessService.resolveAccess(page);
        if (!access.isViewable()) {
            throw new SpacePermissionException();
        }
        RepoShareLinkDomain link = repoShareLinkRepository.listByPageId(pageId).stream()
                .filter(l -> Objects.equals(l.getId(), linkId))
                .findFirst()
                .orElseThrow(() -> BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound));
        Long userId = RequestContext.get().getUserId();
        if (!access.isManageable() && !Objects.equals(link.getCreatedBy(), userId)) {
            throw new SpacePermissionException();
        }
        repoShareLinkRepository.softDelete(linkId);
        // 级联清理该链接产生的副作用：自动加入「与我共享」的记录 + 「最近访问」记录 + 访问统计日志
        int shareUsers = repoShareUserRepository.softDeleteByLinkId(linkId);
        int visits = repoPageVisitRepository.removeByLinkId(linkId);
        int visitLogs = repoShareLinkVisitRepository.removeByLinkId(linkId);
        log.info("repo share link deleted: linkId={}, pageId={}, userId={}, cleanedShareUsers={}, cleanedVisits={}, cleanedVisitLogs={}",
                linkId, pageId, userId, shareUsers, visits, visitLogs);
    }

    @Override
    public RepoShareLinkStatsVO getShareLinkStats(Long pageId, Long linkId) {
        RepoPageDomain page = mustGetPage(pageId);
        RepoAccessResult access = repoAccessService.resolveAccess(page);
        if (!access.isViewable()) {
            throw new SpacePermissionException();
        }
        // 链接须属于该页面（防跨页猜 ID 统计他页链接）
        repoShareLinkRepository.listByPageId(pageId).stream()
                .filter(l -> Objects.equals(l.getId(), linkId))
                .findFirst()
                .orElseThrow(() -> BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound));

        List<RepoShareLinkVisitDomain> visits = repoShareLinkVisitRepository.listByLinkId(linkId);
        // 访问日志按 visited_at 倒序，首次出现的 user 即其最近访问；LinkedHashMap 保序 → 用户列表天然按最近访问倒序。
        Map<Long, Date> lastByUser = new LinkedHashMap<>();
        for (RepoShareLinkVisitDomain visit : visits) {
            Date prev = lastByUser.get(visit.getUserId());
            if (prev == null || visit.getVisitedAt().after(prev)) {
                lastByUser.put(visit.getUserId(), visit.getVisitedAt());
            }
        }
        List<Long> userIds = new ArrayList<>(lastByUser.keySet());
        Map<Long, UserDto> userInfos = loadUserInfos(new HashSet<>(userIds));
        List<RepoShareLinkVisitUserVO> users = new ArrayList<>();
        for (Long userId : userIds) {
            UserDto user = userInfos.get(userId);
            String name = user == null ? null
                    : (user.getNickName() == null || user.getNickName().isBlank() ? user.getUserName() : user.getNickName());
            users.add(RepoShareLinkVisitUserVO.builder()
                    .userId(userId)
                    .userName(name)
                    .avatar(user == null ? null : user.getAvatar())
                    .lastVisitedAt(lastByUser.get(userId))
                    .build());
        }
        return RepoShareLinkStatsVO.builder()
                .visitCount(visits.size())
                .users(users)
                .build();
    }

    @Override
    public RepoAclStatus getAcl(Long pageId) {
        RepoPageDomain page = mustGetPage(pageId);
        repoAccessService.requireManageable(page);
        return RepoAclStatus.builder()
                .aclEnabled(page.getAclEnabled() == null ? 0 : page.getAclEnabled())
                .entries(repoPageAclRepository.listByPageId(pageId))
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void putAcl(Long pageId, Integer aclEnabled, List<RepoPageAclDomain> entries) {
        RepoPageDomain page = mustGetPage(pageId);
        repoAccessService.requireManageable(page);
        int enabled = aclEnabled != null && aclEnabled == 1 ? 1 : 0;

        List<RepoPageAclDomain> normalized = new ArrayList<>();
        if (entries != null) {
            Set<Long> seen = new HashSet<>();
            for (RepoPageAclDomain entry : entries) {
                if (entry == null || entry.getUserId() == null || seen.contains(entry.getUserId())) {
                    continue;
                }
                seen.add(entry.getUserId());
                normalized.add(RepoPageAclDomain.builder()
                        .tenantId(RequestContext.get().getTenantId())
                        .userId(entry.getUserId())
                        .permBits(normalizePermBits(entry.getPermBits()))
                        .build());
            }
        }
        repoPageAclRepository.replaceAll(pageId, normalized);
        repoPageRepository.updateAclEnabled(pageId, enabled);
        log.info("repo page acl updated: pageId={}, aclEnabled={}, entries={}, userId={}",
                pageId, enabled, normalized.size(), RequestContext.get().getUserId());
    }

    /**
     * upsert 空间分享：有效记录覆盖权限位；软删记录复活（唯一键含 yn，
     * 反复分享→取消不能重复插入新行）。
     */
    private void upsertShare(RepoPageDomain page, Long targetSpaceId, int bits, Long userId, Long tenantId) {
        RepoShareDomain existing = repoShareRepository.findByPageIdAndTargetSpaceIdIncludeDeleted(page.getId(), targetSpaceId);
        if (existing == null) {
            repoShareRepository.insert(RepoShareDomain.builder()
                    .tenantId(tenantId)
                    .pageId(page.getId())
                    .targetSpaceId(targetSpaceId)
                    .permBits(bits)
                    .createdBy(userId)
                    .build());
            return;
        }
        if (existing.getYn() != null && existing.getYn() == RepoConstants.YN_VALID) {
            repoShareRepository.updatePermBits(existing.getId(), bits);
        } else {
            repoShareRepository.revive(existing.getId(), bits, userId);
        }
    }

    /**
     * upsert 个人分享：有效记录覆盖权限位；软删记录复活（唯一键含 yn，
     * 反复分享→取消不能重复插入新行）。
     */
    private void upsertShareUser(RepoPageDomain page, Long targetUserId, int bits, Long userId, Long tenantId) {
        RepoShareUserDomain existing = repoShareUserRepository.findByPageIdAndTargetUserIdIncludeDeleted(page.getId(), targetUserId);
        if (existing == null) {
            repoShareUserRepository.insert(RepoShareUserDomain.builder()
                    .tenantId(tenantId)
                    .pageId(page.getId())
                    .targetUserId(targetUserId)
                    .permBits(bits)
                    .createdBy(userId)
                    .build());
            return;
        }
        if (existing.getYn() != null && existing.getYn() == RepoConstants.YN_VALID) {
            // 个人分享覆盖链接来源（link_id=NULL），避免「链接自动加入」记录被个人分享复用后仍受链接删除牵连
            repoShareUserRepository.updatePermBits(existing.getId(), bits, null);
        } else {
            repoShareUserRepository.revive(existing.getId(), bits, userId, null);
        }
    }

    /**
     * 向被分享用户发送 Private 站内通知（需求3）；失败仅告警，不影响分享写入。
     */
    private void sendShareNotifications(Long tenantId, Long senderId, RepoPageDomain page, List<Long> targetUserIds) {
        if (targetUserIds.isEmpty()) {
            return;
        }
        try {
            String senderName = loadUserNames(Set.of(senderId)).get(senderId);
            String content = (senderName == null ? "有人" : "「" + senderName + "」")
                    + "将文档" + pageRefForNotify(page) + "分享给了你";
            SendNotifyMessageDto dto = SendNotifyMessageDto.builder()
                    .tenantId(tenantId)
                    .senderId(senderId)
                    .content(content)
                    .scope(NotifyMessage.MessageScope.Private)
                    .userIds(targetUserIds)
                    .build();
            notifyMessageApplicationService.sendNotifyMessage(dto);
        } catch (Exception e) {
            log.warn("repo share user notify failed, pageId={}, targets={}", page.getId(), targetUserIds, e);
        }
    }

    /**
     * 通知正文里的文档引用（需求4）：有 slugId 时输出 Markdown 链接——IM 消息气泡按 markdown 渲染，
     * 相对路径 /repo/doc/{slugId} 由前端 safeHref 补 target=_blank 在新窗口打开；无 slugId 时退化为
     * 「标题」纯文本。标题里的半角 [ ] 换成全角，避免提前闭合链接语法把正文截断（不依赖下游渲染器
     * 对反斜杠转义的支持）。
     */
    private String pageRefForNotify(RepoPageDomain page) {
        String title = page.getTitle() == null ? "" : page.getTitle();
        String slugId = page.getSlugId();
        if (slugId == null || slugId.isEmpty()) {
            return "「" + title + "」";
        }
        String safeTitle = title.replace('[', '［').replace(']', '］');
        return "「[" + safeTitle + "](/repo/doc/" + slugId + ")」";
    }

    /**
     * 权限位归一化：必须含 VIEW，掩码到已知位。
     */
    private int normalizePermBits(Integer permBits) {
        if (permBits == null || permBits < 0) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                    "permBits required");
        }
        int bits = permBits & RepoConstants.PERM_FULL;
        if ((bits & RepoConstants.PERM_VIEW) == 0) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                    "permBits must include VIEW");
        }
        return bits;
    }

    /**
     * 把要授予的权限位裁剪到当前用户持有范围内（分享的权限不能超过自己已有的权限）。
     * 能进入分享流程者（shareable 或 manageable）必可查看，故查看位恒保留。
     */
    private int clampToHeld(int bits, RepoAccessResult access) {
        int held = access.getPermBits();
        int clamped = bits & held;
        if ((clamped & RepoConstants.PERM_VIEW) == 0) {
            clamped |= RepoConstants.PERM_VIEW;
        }
        return clamped;
    }

    private String generateUniqueToken() {
        for (int i = 0; i < TOKEN_MAX_RETRY; i++) {
            StringBuilder sb = new StringBuilder(RepoConstants.SHARE_LINK_TOKEN_LENGTH);
            for (int j = 0; j < RepoConstants.SHARE_LINK_TOKEN_LENGTH; j++) {
                sb.append(TOKEN_ALPHABET[RANDOM.nextInt(TOKEN_ALPHABET.length)]);
            }
            String token = sb.toString();
            if (repoShareLinkRepository.findByToken(token) == null) {
                return token;
            }
        }
        throw BizException.of(ErrorCodeEnum.SYS_ERROR, BizExceptionCodeEnum.systemUnexpectedError);
    }

    /**
     * 静默访问裁决：异常或无权均返回 false，不打断列表主流程。
     */
    private boolean isViewableQuietly(RepoPageDomain page) {
        try {
            return repoAccessService.resolveAccess(page).isViewable();
        } catch (Exception e) {
            log.warn("repo share list resolve access failed, pageId={}", page.getId(), e);
            return false;
        }
    }

    /**
     * 批量解析用户完整信息（展示名/头像用）；失败返回空 Map 不阻塞。
     */
    private Map<Long, UserDto> loadUserInfos(Set<Long> userIds) {
        Map<Long, UserDto> users = new HashMap<>();
        if (userIds.isEmpty()) {
            return users;
        }
        try {
            List<UserDto> list = userApplicationService.queryUserListByIds(new ArrayList<>(userIds));
            if (list != null) {
                for (UserDto user : list) {
                    users.put(user.getId(), user);
                }
            }
        } catch (Exception e) {
            log.warn("repo share stats load users failed, ids={}", userIds, e);
        }
        return users;
    }

    /**
     * 批量解析用户展示名（昵称优先，空则用户名）；失败返回空 Map 不阻塞。
     */
    private Map<Long, String> loadUserNames(Set<Long> userIds) {
        Map<Long, String> names = new HashMap<>();
        if (userIds.isEmpty()) {
            return names;
        }
        try {
            List<UserDto> users = userApplicationService.queryUserListByIds(new ArrayList<>(userIds));
            if (users != null) {
                for (UserDto user : users) {
                    String name = user.getNickName();
                    if (name == null || name.isBlank()) {
                        name = user.getUserName();
                    }
                    names.put(user.getId(), name);
                }
            }
        } catch (Exception e) {
            log.warn("repo share list load user names failed, ids={}", userIds, e);
        }
        return names;
    }

    /**
     * 空间分享记录补目标空间名称与类型（授权管理按 Team/Group 分组展示）。
     * 空间已被删除时两项留空，由前端退化为「空间 #id」。
     */
    private List<RepoShareSpaceVO> toSpaceShareVOs(List<RepoShareDomain> shares) {
        List<RepoShareSpaceVO> vos = new ArrayList<>();
        if (shares == null || shares.isEmpty()) {
            return vos;
        }
        Set<Long> spaceIds = new HashSet<>();
        for (RepoShareDomain share : shares) {
            if (share.getTargetSpaceId() != null) {
                spaceIds.add(share.getTargetSpaceId());
            }
        }
        Map<Long, SpaceDto> spaceMap = new HashMap<>();
        if (!spaceIds.isEmpty()) {
            try {
                List<SpaceDto> spaces = spaceApplicationService.queryByIds(new ArrayList<>(spaceIds));
                if (spaces != null) {
                    for (SpaceDto space : spaces) {
                        spaceMap.put(space.getId(), space);
                    }
                }
            } catch (Exception e) {
                // 名称解析失败不影响分享记录本身，前端会退化为 ID 展示
                log.warn("repo share list load spaces failed, ids={}", spaceIds, e);
            }
        }
        for (RepoShareDomain share : shares) {
            SpaceDto space = spaceMap.get(share.getTargetSpaceId());
            vos.add(RepoShareSpaceVO.from(share,
                    space == null ? null : space.getName(),
                    space == null || space.getType() == null ? null : space.getType().name()));
        }
        return vos;
    }

    /**
     * 批量解析空间名；失败返回空 Map 不阻塞。
     */
    private Map<Long, String> loadSpaceNames(Set<Long> spaceIds) {
        Map<Long, String> names = new HashMap<>();
        if (spaceIds.isEmpty()) {
            return names;
        }
        try {
            List<SpaceDto> spaces = spaceApplicationService.queryByIds(new ArrayList<>(spaceIds));
            if (spaces != null) {
                for (SpaceDto space : spaces) {
                    names.put(space.getId(), space.getName());
                }
            }
        } catch (Exception e) {
            log.warn("repo share list load space names failed, ids={}", spaceIds, e);
        }
        return names;
    }

    private RepoPageDomain mustGetPage(Long pageId) {
        RepoPageDomain page = repoPageRepository.findById(pageId);
        if (page == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound);
        }
        return page;
    }
}
