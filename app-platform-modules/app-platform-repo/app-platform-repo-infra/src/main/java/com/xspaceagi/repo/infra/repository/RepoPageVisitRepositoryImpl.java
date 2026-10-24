package com.xspaceagi.repo.infra.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xspaceagi.repo.domain.model.RepoPageVisitDomain;
import com.xspaceagi.repo.domain.repository.RepoPageVisitRepository;
import com.xspaceagi.repo.infra.dao.entity.RepoPageVisit;
import com.xspaceagi.repo.infra.dao.mapper.RepoPageVisitMapper;
import com.xspaceagi.repo.spec.constants.RepoConstants;
import com.xspaceagi.system.spec.common.RequestContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 文档访问记录仓储实现（需求1：门户最近访问）。
 */
@Repository
@RequiredArgsConstructor
public class RepoPageVisitRepositoryImpl implements RepoPageVisitRepository {

    private final RepoPageVisitMapper repoPageVisitMapper;

    @Override
    public void upsert(Long userId, Long pageId, Long linkId) {
        Date now = new Date();
        RepoPageVisit existing = repoPageVisitMapper.selectOne(new LambdaQueryWrapper<RepoPageVisit>()
                .eq(RepoPageVisit::getUserId, userId)
                .eq(RepoPageVisit::getPageId, pageId)
                .eq(RepoPageVisit::getYn, RepoConstants.YN_VALID)
                .last("LIMIT 1"));
        if (existing != null) {
            LambdaUpdateWrapper<RepoPageVisit> wrapper = new LambdaUpdateWrapper<RepoPageVisit>()
                    .eq(RepoPageVisit::getId, existing.getId())
                    .set(RepoPageVisit::getLastVisitedAt, now)
                    .set(RepoPageVisit::getModified, now);
            // 覆盖链接来源：正常访问传 NULL 需显式清空（MyBatis-Plus NOT_NULL 策略跳过 null）
            if (linkId != null) {
                wrapper.set(RepoPageVisit::getLinkId, linkId);
            } else {
                wrapper.setSql("link_id = NULL");
            }
            repoPageVisitMapper.update(null, wrapper);
            return;
        }
        RepoPageVisit entity = RepoPageVisit.builder()
                .tenantId(RequestContext.get().getTenantId())
                .userId(userId)
                .pageId(pageId)
                .linkId(linkId)
                .lastVisitedAt(now)
                .created(now)
                .modified(now)
                .yn(RepoConstants.YN_VALID)
                .build();
        repoPageVisitMapper.insert(entity);
    }

    @Override
    public List<RepoPageVisitDomain> listByUserId(Long userId, int limit) {
        return repoPageVisitMapper.selectList(new LambdaQueryWrapper<RepoPageVisit>()
                        .eq(RepoPageVisit::getUserId, userId)
                        .eq(RepoPageVisit::getYn, RepoConstants.YN_VALID)
                        .orderByDesc(RepoPageVisit::getLastVisitedAt)
                        .last("LIMIT " + Math.max(limit, 1)))
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public boolean remove(Long userId, Long pageId) {
        return repoPageVisitMapper.delete(new LambdaQueryWrapper<RepoPageVisit>()
                .eq(RepoPageVisit::getUserId, userId)
                .eq(RepoPageVisit::getPageId, pageId)) > 0;
    }

    @Override
    public int removeByLinkId(Long linkId) {
        if (linkId == null) {
            return 0;
        }
        return repoPageVisitMapper.delete(new LambdaQueryWrapper<RepoPageVisit>()
                .eq(RepoPageVisit::getLinkId, linkId));
    }

    private RepoPageVisitDomain toDomain(RepoPageVisit entity) {
        return RepoPageVisitDomain.builder()
                .id(entity.getId())
                .tenantId(entity.getTenantId())
                .userId(entity.getUserId())
                .pageId(entity.getPageId())
                .linkId(entity.getLinkId())
                .lastVisitedAt(entity.getLastVisitedAt())
                .created(entity.getCreated())
                .modified(entity.getModified())
                .build();
    }
}
