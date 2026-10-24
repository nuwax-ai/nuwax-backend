package com.xspaceagi.repo.application.service.impl;

import com.xspaceagi.repo.application.service.RepoAccessService;
import com.xspaceagi.repo.application.service.RepoPortalApplicationService;
import com.xspaceagi.repo.application.service.dto.RepoPortalPageVO;
import com.xspaceagi.repo.domain.model.RepoPageDomain;
import com.xspaceagi.repo.domain.model.RepoPageFavoriteDomain;
import com.xspaceagi.repo.domain.model.RepoPageVisitDomain;
import com.xspaceagi.repo.domain.model.RepoShareDomain;
import com.xspaceagi.repo.domain.model.RepoShareLinkDomain;
import com.xspaceagi.repo.domain.model.RepoShareLinkVisitDomain;
import com.xspaceagi.repo.domain.model.RepoShareUserDomain;
import com.xspaceagi.repo.domain.repository.RepoPageRepository;
import com.xspaceagi.repo.domain.repository.RepoPageFavoriteRepository;
import com.xspaceagi.repo.domain.repository.RepoPageVisitRepository;
import com.xspaceagi.repo.domain.repository.RepoShareLinkRepository;
import com.xspaceagi.repo.domain.repository.RepoShareLinkVisitRepository;
import com.xspaceagi.repo.domain.repository.RepoShareRepository;
import com.xspaceagi.repo.domain.repository.RepoShareUserRepository;
import com.xspaceagi.repo.spec.enums.RepoPageTypeEnum;
import com.xspaceagi.system.application.dto.SpaceDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.infra.dao.entity.Space;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 门户应用服务实现（需求1：飞书式「最近」门户）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepoPortalApplicationServiceImpl implements RepoPortalApplicationService {

    private static final int DEFAULT_SIZE = 30;

    private static final int MAX_SIZE = 200;

    /**
     * 位置类型：我的资料（个人空间）
     */
    private static final String LOCATION_PERSONAL = "personal";

    /**
     * 位置类型：空间名
     */
    private static final String LOCATION_SPACE = "space";

    /**
     * 位置类型：与我共享
     */
    private static final String LOCATION_SHARED = "shared";

    /**
     * 位置类型：分享给个人（需求3）
     */
    private static final String LOCATION_SHARED_USER = "shared-user";

    private static final String PERSONAL_LABEL = "我的资料";

    private static final String SHARED_LABEL = "与我共享";

    private static final String SHARED_USER_LABEL = "分享给我";

    private final RepoPageVisitRepository repoPageVisitRepository;

    private final RepoShareLinkVisitRepository repoShareLinkVisitRepository;

    private final RepoPageFavoriteRepository repoPageFavoriteRepository;

    private final RepoPageRepository repoPageRepository;

    private final RepoShareRepository repoShareRepository;

    private final RepoShareUserRepository repoShareUserRepository;

    private final RepoShareLinkRepository repoShareLinkRepository;

    private final RepoAccessService repoAccessService;

    private final SpacePermissionService spacePermissionService;

    private final SpaceApplicationService spaceApplicationService;

    private final UserApplicationService userApplicationService;

    @Override
    public List<RepoPortalPageVO> listRecentlyAccessed(int from, int size) {
        Long userId = RequestContext.get().getUserId();
        int limit = size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        int offset = Math.max(from, 0);
        int candidateLimit = Math.max(500, offset + limit + 50);

        Map<Long, Date> timeByPage = new HashMap<>();
        Set<Long> pageIds = new LinkedHashSet<>();
        // 访问记录
        for (RepoPageVisitDomain visit : repoPageVisitRepository.listByUserId(userId, candidateLimit)) {
            pageIds.add(visit.getPageId());
            if (visit.getLastVisitedAt() != null) {
                timeByPage.merge(visit.getPageId(), visit.getLastVisitedAt(), this::maxDate);
            }
        }
        // 我编辑过的（LIKE 粗筛后按 editorIds/lastEditorId 精确过滤）
        for (RepoPageDomain page : repoPageRepository.listRecentByEditor(userId, candidateLimit)) {
            boolean edited = userId.equals(page.getLastEditorId())
                    || (page.getEditorIds() != null && page.getEditorIds().contains(userId));
            if (!edited) {
                continue;
            }
            pageIds.add(page.getId());
            if (page.getEditedAt() != null) {
                timeByPage.merge(page.getId(), page.getEditedAt(), this::maxDate);
            }
        }
        if (pageIds.isEmpty()) {
            return new ArrayList<>();
        }
        List<RepoPageDomain> pages = repoPageRepository.listByIds(new ArrayList<>(pageIds));
        return buildVOs(pages, userId, timeByPage, offset, limit);
    }

    @Override
    public List<RepoPortalPageVO> listSharedByMe() {
        Long userId = RequestContext.get().getUserId();
        Map<Long, Date> timeByPage = new HashMap<>();
        for (RepoShareDomain share : repoShareRepository.listByCreatedBy(userId)) {
            if (share.getCreated() != null) {
                timeByPage.merge(share.getPageId(), share.getCreated(), this::maxDate);
            }
        }
        for (RepoShareLinkDomain link : repoShareLinkRepository.listByCreatedBy(userId)) {
            if (link.getCreated() != null) {
                timeByPage.merge(link.getPageId(), link.getCreated(), this::maxDate);
            }
        }
        if (timeByPage.isEmpty()) {
            return new ArrayList<>();
        }
        List<RepoPageDomain> pages = repoPageRepository.listByIds(new ArrayList<>(timeByPage.keySet()));
        return buildVOs(pages, userId, timeByPage, 0, MAX_SIZE);
    }

    @Override
    public List<RepoPortalPageVO> listSharedWithMe() {
        Long userId = RequestContext.get().getUserId();
        List<Long> mySpaceIds = spacePermissionService.querySpaceIdList(userId);
        Map<Long, Date> timeByPage = new HashMap<>();
        if (mySpaceIds != null && !mySpaceIds.isEmpty()) {
            for (RepoShareDomain share : repoShareRepository.listByTargetSpaceIds(mySpaceIds)) {
                if (share.getCreated() != null) {
                    timeByPage.merge(share.getPageId(), share.getCreated(), this::maxDate);
                }
            }
        }
        // 需求3：分享给个人记录并入「与我共享」（分享给我即算，不做外部过滤）
        Set<Long> userSharedPageIds = new HashSet<>();
        for (RepoShareUserDomain share : repoShareUserRepository.listByTargetUserId(userId)) {
            userSharedPageIds.add(share.getPageId());
            if (share.getCreated() != null) {
                timeByPage.merge(share.getPageId(), share.getCreated(), this::maxDate);
            }
        }
        if (timeByPage.isEmpty()) {
            return new ArrayList<>();
        }
        List<RepoPageDomain> pages = repoPageRepository.listByIds(new ArrayList<>(timeByPage.keySet()));
        Set<Long> mySpaceSet = mySpaceIds == null ? new HashSet<>() : new HashSet<>(mySpaceIds);
        // 空间分享来源仅保留外部页面；个人分享来源不过滤（分享给我即算）
        List<RepoPageDomain> external = new ArrayList<>();
        for (RepoPageDomain page : pages) {
            if (userSharedPageIds.contains(page.getId())) {
                external.add(page);
            } else if (!mySpaceSet.contains(page.getSpaceId()) && !userId.equals(page.getCreatorId())) {
                external.add(page);
            }
        }
        return buildVOs(external, userId, timeByPage, 0, MAX_SIZE, userSharedPageIds);
    }

    @Override
    public void recordVisit(Long pageId, Long linkId) {
        try {
            if (pageId == null || RequestContext.get() == null || RequestContext.get().getUserId() == null) {
                return;
            }
            Long userId = RequestContext.get().getUserId();
            repoPageVisitRepository.upsert(userId, pageId, linkId);
            // 通过分享链接访问时追加一条访问日志（append-only，供链接「统计」用）
            if (linkId != null) {
                repoShareLinkVisitRepository.insert(RepoShareLinkVisitDomain.builder()
                        .tenantId(RequestContext.get().getTenantId())
                        .linkId(linkId)
                        .pageId(pageId)
                        .userId(userId)
                        .visitedAt(new Date())
                        .build());
            }
        } catch (Exception e) {
            // 访问记录失败不影响页面打开主流程
            log.warn("repo portal record visit failed, pageId={}", pageId, e);
        }
    }

    @Override
    public void removeVisit(Long pageId) {
        Long userId = RequestContext.get().getUserId();
        repoPageVisitRepository.remove(userId, pageId);
    }

    @Override
    public List<RepoPortalPageVO> listMyResources(int from, int size) {
        Long userId = RequestContext.get().getUserId();
        List<Long> mySpaceIds = spacePermissionService.querySpaceIdList(userId);
        if (mySpaceIds == null || mySpaceIds.isEmpty()) {
            return new ArrayList<>();
        }
        int limit = size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        int offset = Math.max(from, 0);
        List<RepoPageDomain> pages = repoPageRepository.listBySpaceIds(mySpaceIds, offset, limit);
        if (pages.isEmpty()) {
            return new ArrayList<>();
        }
        return buildVOs(pages, userId, new HashMap<>(), 0, limit);
    }

    @Override
    public List<RepoPortalPageVO> listFavorites(int from, int size) {
        Long userId = RequestContext.get().getUserId();
        int limit = size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        int offset = Math.max(from, 0);
        List<RepoPageFavoriteDomain> favorites = repoPageFavoriteRepository.listByUserId(userId, offset, limit);
        if (favorites.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Long, Date> timeByPage = new HashMap<>();
        List<Long> pageIds = new ArrayList<>();
        for (RepoPageFavoriteDomain fav : favorites) {
            pageIds.add(fav.getPageId());
            if (fav.getCreated() != null) {
                timeByPage.put(fav.getPageId(), fav.getCreated());
            }
        }
        List<RepoPageDomain> pages = repoPageRepository.listByIds(pageIds);
        return buildVOs(pages, userId, timeByPage, 0, limit);
    }

    @Override
    public void favorite(Long pageId) {
        Long userId = RequestContext.get().getUserId();
        RepoPageDomain page = repoPageRepository.findById(pageId);
        if (page == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound);
        }
        repoAccessService.requireViewable(page);
        repoPageFavoriteRepository.add(userId, pageId);
    }

    @Override
    public void unfavorite(Long pageId) {
        Long userId = RequestContext.get().getUserId();
        repoPageFavoriteRepository.remove(userId, pageId);
    }

    @Override
    public boolean isFavorite(Long pageId) {
        Long userId = RequestContext.get().getUserId();
        return repoPageFavoriteRepository.isFavorite(userId, pageId);
    }

    /**
     * 页面列表 → 门户 VO：逐页权限过滤、时间倒序、批量填充位置与所有者名。
     */
    private List<RepoPortalPageVO> buildVOs(List<RepoPageDomain> pages, Long userId,
                                            Map<Long, Date> timeByPage, int from, int limit) {
        return buildVOs(pages, userId, timeByPage, from, limit, new HashSet<>());
    }

    /**
     * 页面列表 → 门户 VO（需求3：userSharedPageIds 为个人分享来源，位置列标「分享给我」）。
     */
    private List<RepoPortalPageVO> buildVOs(List<RepoPageDomain> pages, Long userId,
                                            Map<Long, Date> timeByPage, int from, int limit,
                                            Set<Long> userSharedPageIds) {
        // 文件夹是纯容器，门户各列表不展示；先剔除再按展示时间倒序
        List<RepoPageDomain> candidates = new ArrayList<>();
        for (RepoPageDomain page : pages) {
            if (!RepoPageTypeEnum.FOLDER.getCode().equals(page.getPageType())) {
                candidates.add(page);
            }
        }
        // 先按展示时间倒序（排序不依赖权限裁决），再逐个裁决可查看——
        // 最近访问候选集可达数百页，逐页 resolveAccess 会 N+1 查空间角色/分享表；
        // 改成从最新开始裁决、凑够本页条数即停，把裁决次数从「全量候选」压到「约一页」。
        candidates.sort(Comparator.comparing((RepoPageDomain p) -> timeOf(p, timeByPage),
                Comparator.nullsLast(Comparator.reverseOrder())));
        int offset = Math.max(from, 0);
        int need = offset + limit;
        List<RepoPageDomain> viewable = new ArrayList<>();
        for (RepoPageDomain page : candidates) {
            if (viewable.size() >= need) {
                break;
            }
            try {
                if (repoAccessService.resolveAccess(page).isViewable()) {
                    viewable.add(page);
                }
            } catch (Exception e) {
                log.warn("repo portal resolve access failed, pageId={}", page.getId(), e);
            }
        }
        if (offset >= viewable.size()) {
            return new ArrayList<>();
        }
        int end = Math.min(viewable.size(), offset + limit);
        viewable = new ArrayList<>(viewable.subList(offset, end));
        if (viewable.isEmpty()) {
            return new ArrayList<>();
        }

        List<Long> mySpaceIds = spacePermissionService.querySpaceIdList(userId);
        Set<Long> mySpaceSet = mySpaceIds == null ? new HashSet<>() : new HashSet<>(mySpaceIds);
        Map<Long, SpaceDto> spaceById = loadSpaces(viewable, mySpaceSet);
        Long personalSpaceId = findPersonalSpaceId(mySpaceSet, spaceById);
        Map<Long, String> ownerNames = loadOwnerNames(viewable);

        List<RepoPortalPageVO> result = new ArrayList<>();
        for (RepoPageDomain page : viewable) {
            String locationType;
            String spaceName;
            if (userSharedPageIds.contains(page.getId())) {
                // 需求3：个人分享来源优先标「分享给我」
                locationType = LOCATION_SHARED_USER;
                spaceName = SHARED_USER_LABEL;
            } else if (Objects.equals(page.getSpaceId(), personalSpaceId)) {
                locationType = LOCATION_PERSONAL;
                spaceName = PERSONAL_LABEL;
            } else if (mySpaceSet.contains(page.getSpaceId())) {
                locationType = LOCATION_SPACE;
                SpaceDto space = spaceById.get(page.getSpaceId());
                spaceName = space == null ? null : space.getName();
            } else {
                locationType = LOCATION_SHARED;
                spaceName = SHARED_LABEL;
            }
            result.add(RepoPortalPageVO.builder()
                    .id(page.getId())
                    .slugId(page.getSlugId())
                    .title(page.getTitle())
                    .pageType(page.getPageType())
                    .sourceExt(page.getSourceExt())
                    .spaceId(page.getSpaceId())
                    .ownerId(page.getCreatorId())
                    .ownerName(page.getCreatorId() == null ? null : ownerNames.get(page.getCreatorId()))
                    .creator(userId != null && userId.equals(page.getCreatorId()))
                    .locationType(locationType)
                    .spaceName(spaceName)
                    .time(timeOf(page, timeByPage))
                    .contentSize(page.getContentSize())
                    .attachmentSize(page.getMediaSize())
                    .build());
        }
        return result;
    }

    /**
     * 展示时间：优先访问/分享时间，缺省回退编辑时间、创建时间。
     */
    private Date timeOf(RepoPageDomain page, Map<Long, Date> timeByPage) {
        Date time = timeByPage.get(page.getId());
        if (time != null) {
            return time;
        }
        return page.getEditedAt() != null ? page.getEditedAt() : page.getCreated();
    }

    /**
     * 批量查空间（页面所属空间 ∪ 我的空间）；失败返回空 Map 不阻断。
     */
    private Map<Long, SpaceDto> loadSpaces(List<RepoPageDomain> pages, Set<Long> mySpaceSet) {
        Set<Long> spaceIds = new HashSet<>(mySpaceSet);
        for (RepoPageDomain page : pages) {
            if (page.getSpaceId() != null) {
                spaceIds.add(page.getSpaceId());
            }
        }
        Map<Long, SpaceDto> spaceById = new HashMap<>();
        if (spaceIds.isEmpty()) {
            return spaceById;
        }
        try {
            List<SpaceDto> spaces = spaceApplicationService.queryByIds(new ArrayList<>(spaceIds));
            if (spaces != null) {
                for (SpaceDto space : spaces) {
                    spaceById.put(space.getId(), space);
                }
            }
        } catch (Exception e) {
            log.warn("repo portal load spaces failed, ids={}", spaceIds, e);
        }
        return spaceById;
    }

    /**
     * 我的空间中的个人空间（「我的资料」），无则返回 null。
     */
    private Long findPersonalSpaceId(Set<Long> mySpaceSet, Map<Long, SpaceDto> spaceById) {
        for (Long spaceId : mySpaceSet) {
            SpaceDto space = spaceById.get(spaceId);
            if (space != null && Space.Type.Personal.equals(space.getType())) {
                return spaceId;
            }
        }
        return null;
    }

    /**
     * 批量查所有者名称：昵称优先，无昵称用用户名；失败返回空 Map 不阻断。
     */
    private Map<Long, String> loadOwnerNames(List<RepoPageDomain> pages) {
        Set<Long> ownerIds = new HashSet<>();
        for (RepoPageDomain page : pages) {
            if (page.getCreatorId() != null) {
                ownerIds.add(page.getCreatorId());
            }
        }
        Map<Long, String> names = new HashMap<>();
        if (ownerIds.isEmpty()) {
            return names;
        }
        try {
            List<UserDto> users = userApplicationService.queryUserListByIds(new ArrayList<>(ownerIds));
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
            log.warn("repo portal load owner names failed, ids={}", ownerIds, e);
        }
        return names;
    }

    private Date maxDate(Date a, Date b) {
        return a.after(b) ? a : b;
    }
}
