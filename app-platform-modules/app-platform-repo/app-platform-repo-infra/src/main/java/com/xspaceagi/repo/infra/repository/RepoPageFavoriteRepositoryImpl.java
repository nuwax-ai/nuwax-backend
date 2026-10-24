package com.xspaceagi.repo.infra.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xspaceagi.repo.domain.model.RepoPageFavoriteDomain;
import com.xspaceagi.repo.domain.repository.RepoPageFavoriteRepository;
import com.xspaceagi.repo.infra.dao.entity.RepoPageFavorite;
import com.xspaceagi.repo.infra.dao.mapper.RepoPageFavoriteMapper;
import com.xspaceagi.repo.spec.constants.RepoConstants;
import com.xspaceagi.system.spec.common.RequestContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 文档收藏仓储实现（门户「我的收藏」）。
 */
@Repository
@RequiredArgsConstructor
public class RepoPageFavoriteRepositoryImpl implements RepoPageFavoriteRepository {

    private final RepoPageFavoriteMapper repoPageFavoriteMapper;

    @Override
    public void add(Long userId, Long pageId) {
        Date now = new Date();
        // 不按 yn 过滤：软删记录复活复用主键，避免 uk_user_page 唯一约束冲突
        RepoPageFavorite existing = repoPageFavoriteMapper.selectOne(new LambdaQueryWrapper<RepoPageFavorite>()
                .eq(RepoPageFavorite::getUserId, userId)
                .eq(RepoPageFavorite::getPageId, pageId)
                .last("LIMIT 1"));
        if (existing != null) {
            repoPageFavoriteMapper.update(null, new LambdaUpdateWrapper<RepoPageFavorite>()
                    .eq(RepoPageFavorite::getId, existing.getId())
                    .set(RepoPageFavorite::getYn, RepoConstants.YN_VALID)
                    .set(RepoPageFavorite::getCreated, now)
                    .set(RepoPageFavorite::getModified, now));
            return;
        }
        RepoPageFavorite entity = RepoPageFavorite.builder()
                .tenantId(RequestContext.get().getTenantId())
                .userId(userId)
                .pageId(pageId)
                .created(now)
                .modified(now)
                .yn(RepoConstants.YN_VALID)
                .build();
        repoPageFavoriteMapper.insert(entity);
    }

    @Override
    public boolean remove(Long userId, Long pageId) {
        return repoPageFavoriteMapper.update(null, new LambdaUpdateWrapper<RepoPageFavorite>()
                .eq(RepoPageFavorite::getUserId, userId)
                .eq(RepoPageFavorite::getPageId, pageId)
                .eq(RepoPageFavorite::getYn, RepoConstants.YN_VALID)
                .set(RepoPageFavorite::getYn, RepoConstants.YN_DELETED)
                .set(RepoPageFavorite::getModified, new Date())) > 0;
    }

    @Override
    public boolean isFavorite(Long userId, Long pageId) {
        return repoPageFavoriteMapper.selectCount(new LambdaQueryWrapper<RepoPageFavorite>()
                .eq(RepoPageFavorite::getUserId, userId)
                .eq(RepoPageFavorite::getPageId, pageId)
                .eq(RepoPageFavorite::getYn, RepoConstants.YN_VALID)) > 0;
    }

    @Override
    public List<RepoPageFavoriteDomain> listByUserId(Long userId, int offset, int size) {
        return repoPageFavoriteMapper.selectList(new LambdaQueryWrapper<RepoPageFavorite>()
                        .eq(RepoPageFavorite::getUserId, userId)
                        .eq(RepoPageFavorite::getYn, RepoConstants.YN_VALID)
                        .orderByDesc(RepoPageFavorite::getCreated)
                        .last("LIMIT " + Math.max(offset, 0) + ", " + Math.max(size, 1)))
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    private RepoPageFavoriteDomain toDomain(RepoPageFavorite entity) {
        return RepoPageFavoriteDomain.builder()
                .id(entity.getId())
                .tenantId(entity.getTenantId())
                .userId(entity.getUserId())
                .pageId(entity.getPageId())
                .created(entity.getCreated())
                .modified(entity.getModified())
                .build();
    }
}
