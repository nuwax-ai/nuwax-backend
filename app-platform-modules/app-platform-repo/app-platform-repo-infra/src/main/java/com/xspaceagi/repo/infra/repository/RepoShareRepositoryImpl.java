package com.xspaceagi.repo.infra.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xspaceagi.repo.domain.model.RepoShareDomain;
import com.xspaceagi.repo.domain.repository.RepoShareRepository;
import com.xspaceagi.repo.infra.dao.entity.RepoShare;
import com.xspaceagi.repo.infra.dao.mapper.RepoShareMapper;
import com.xspaceagi.repo.spec.constants.RepoConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 文档空间分享仓储实现。
 */
@Repository
@RequiredArgsConstructor
public class RepoShareRepositoryImpl implements RepoShareRepository {

    private final RepoShareMapper repoShareMapper;

    @Override
    public List<RepoShareDomain> listByPageId(Long pageId) {
        return repoShareMapper.selectList(new LambdaQueryWrapper<RepoShare>()
                        .eq(RepoShare::getPageId, pageId)
                        .eq(RepoShare::getYn, RepoConstants.YN_VALID)
                        .orderByAsc(RepoShare::getId))
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<RepoShareDomain> listByPageIdAndTargetSpaceIds(Long pageId, List<Long> targetSpaceIds) {
        if (targetSpaceIds == null || targetSpaceIds.isEmpty()) {
            return List.of();
        }
        return repoShareMapper.selectList(new LambdaQueryWrapper<RepoShare>()
                        .eq(RepoShare::getPageId, pageId)
                        .in(RepoShare::getTargetSpaceId, targetSpaceIds)
                        .eq(RepoShare::getYn, RepoConstants.YN_VALID))
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<RepoShareDomain> listByPageIdsAndTargetSpaceIds(List<Long> pageIds, List<Long> targetSpaceIds) {
        if (pageIds == null || pageIds.isEmpty() || targetSpaceIds == null || targetSpaceIds.isEmpty()) {
            return List.of();
        }
        return repoShareMapper.selectList(new LambdaQueryWrapper<RepoShare>()
                        .in(RepoShare::getPageId, pageIds)
                        .in(RepoShare::getTargetSpaceId, targetSpaceIds)
                        .eq(RepoShare::getYn, RepoConstants.YN_VALID))
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<RepoShareDomain> listByCreatedBy(Long userId) {
        return repoShareMapper.selectList(new LambdaQueryWrapper<RepoShare>()
                        .eq(RepoShare::getCreatedBy, userId)
                        .eq(RepoShare::getYn, RepoConstants.YN_VALID)
                        .orderByDesc(RepoShare::getCreated))
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<RepoShareDomain> listByTargetSpaceIds(List<Long> targetSpaceIds) {
        if (targetSpaceIds == null || targetSpaceIds.isEmpty()) {
            return List.of();
        }
        return repoShareMapper.selectList(new LambdaQueryWrapper<RepoShare>()
                        .in(RepoShare::getTargetSpaceId, targetSpaceIds)
                        .eq(RepoShare::getYn, RepoConstants.YN_VALID)
                        .orderByDesc(RepoShare::getCreated))
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<RepoShareDomain> listByPageIds(List<Long> pageIds) {
        if (pageIds == null || pageIds.isEmpty()) {
            return List.of();
        }
        return repoShareMapper.selectList(new LambdaQueryWrapper<RepoShare>()
                        .in(RepoShare::getPageId, pageIds)
                        .eq(RepoShare::getYn, RepoConstants.YN_VALID)
                        .orderByDesc(RepoShare::getCreated))
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public RepoShareDomain findByPageIdAndTargetSpaceIdIncludeDeleted(Long pageId, Long targetSpaceId) {
        RepoShare entity = repoShareMapper.selectOne(new LambdaQueryWrapper<RepoShare>()
                .eq(RepoShare::getPageId, pageId)
                .eq(RepoShare::getTargetSpaceId, targetSpaceId)
                .orderByDesc(RepoShare::getId)
                .last("LIMIT 1"));
        return entity == null ? null : toDomain(entity);
    }

    @Override
    public boolean revive(Long id, Integer permBits, Long createdBy) {
        Date now = new Date();
        return repoShareMapper.update(null, new LambdaUpdateWrapper<RepoShare>()
                .eq(RepoShare::getId, id)
                .set(RepoShare::getYn, RepoConstants.YN_VALID)
                .set(RepoShare::getPermBits, permBits)
                .set(RepoShare::getCreatedBy, createdBy)
                .set(RepoShare::getCreated, now)
                .set(RepoShare::getModified, now)) > 0;
    }

    @Override
    public Long insert(RepoShareDomain domain) {
        Date now = new Date();
        RepoShare entity = RepoShare.builder()
                .tenantId(domain.getTenantId())
                .pageId(domain.getPageId())
                .targetSpaceId(domain.getTargetSpaceId())
                .permBits(domain.getPermBits())
                .createdBy(domain.getCreatedBy())
                .created(now)
                .modified(now)
                .yn(RepoConstants.YN_VALID)
                .build();
        repoShareMapper.insert(entity);
        return entity.getId();
    }

    @Override
    public boolean updatePermBits(Long id, Integer permBits) {
        return repoShareMapper.update(null, new LambdaUpdateWrapper<RepoShare>()
                .eq(RepoShare::getId, id)
                .eq(RepoShare::getYn, RepoConstants.YN_VALID)
                .set(RepoShare::getPermBits, permBits)) > 0;
    }

    @Override
    public boolean softDelete(Long id) {
        return repoShareMapper.update(null, new LambdaUpdateWrapper<RepoShare>()
                .eq(RepoShare::getId, id)
                .eq(RepoShare::getYn, RepoConstants.YN_VALID)
                .set(RepoShare::getYn, RepoConstants.YN_DELETED)) > 0;
    }

    @Override
    public int softDeleteByPageId(Long pageId) {
        return repoShareMapper.update(null, new LambdaUpdateWrapper<RepoShare>()
                .eq(RepoShare::getPageId, pageId)
                .eq(RepoShare::getYn, RepoConstants.YN_VALID)
                .set(RepoShare::getYn, RepoConstants.YN_DELETED));
    }

    @Override
    public boolean softDeleteByPageIdAndSpaceId(Long pageId, Long targetSpaceId) {
        return repoShareMapper.update(null, new LambdaUpdateWrapper<RepoShare>()
                .eq(RepoShare::getPageId, pageId)
                .eq(RepoShare::getTargetSpaceId, targetSpaceId)
                .eq(RepoShare::getYn, RepoConstants.YN_VALID)
                .set(RepoShare::getYn, RepoConstants.YN_DELETED)) > 0;
    }

    private RepoShareDomain toDomain(RepoShare entity) {
        return RepoShareDomain.builder()
                .id(entity.getId())
                .tenantId(entity.getTenantId())
                .pageId(entity.getPageId())
                .targetSpaceId(entity.getTargetSpaceId())
                .permBits(entity.getPermBits())
                .createdBy(entity.getCreatedBy())
                .yn(entity.getYn())
                .created(entity.getCreated())
                .modified(entity.getModified())
                .build();
    }
}
