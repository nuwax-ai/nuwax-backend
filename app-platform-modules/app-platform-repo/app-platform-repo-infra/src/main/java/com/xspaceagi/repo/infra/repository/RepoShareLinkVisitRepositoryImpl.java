package com.xspaceagi.repo.infra.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xspaceagi.repo.domain.model.RepoShareLinkVisitDomain;
import com.xspaceagi.repo.domain.repository.RepoShareLinkVisitRepository;
import com.xspaceagi.repo.infra.dao.entity.RepoShareLinkVisit;
import com.xspaceagi.repo.infra.dao.mapper.RepoShareLinkVisitMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 分享链接访问日志仓储实现（分享链接「统计」用，append-only）。
 */
@Repository
@RequiredArgsConstructor
public class RepoShareLinkVisitRepositoryImpl implements RepoShareLinkVisitRepository {

    private final RepoShareLinkVisitMapper repoShareLinkVisitMapper;

    @Override
    public void insert(RepoShareLinkVisitDomain domain) {
        RepoShareLinkVisit entity = RepoShareLinkVisit.builder()
                .tenantId(domain.getTenantId())
                .linkId(domain.getLinkId())
                .pageId(domain.getPageId())
                .userId(domain.getUserId())
                .visitedAt(domain.getVisitedAt())
                .created(new Date())
                .build();
        repoShareLinkVisitMapper.insert(entity);
    }

    @Override
    public List<RepoShareLinkVisitDomain> listByLinkId(Long linkId) {
        return repoShareLinkVisitMapper.selectList(new LambdaQueryWrapper<RepoShareLinkVisit>()
                        .eq(RepoShareLinkVisit::getLinkId, linkId)
                        .orderByDesc(RepoShareLinkVisit::getVisitedAt))
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public int removeByLinkId(Long linkId) {
        if (linkId == null) {
            return 0;
        }
        return repoShareLinkVisitMapper.delete(new LambdaQueryWrapper<RepoShareLinkVisit>()
                .eq(RepoShareLinkVisit::getLinkId, linkId));
    }

    private RepoShareLinkVisitDomain toDomain(RepoShareLinkVisit entity) {
        return RepoShareLinkVisitDomain.builder()
                .id(entity.getId())
                .tenantId(entity.getTenantId())
                .linkId(entity.getLinkId())
                .pageId(entity.getPageId())
                .userId(entity.getUserId())
                .visitedAt(entity.getVisitedAt())
                .created(entity.getCreated())
                .build();
    }
}
