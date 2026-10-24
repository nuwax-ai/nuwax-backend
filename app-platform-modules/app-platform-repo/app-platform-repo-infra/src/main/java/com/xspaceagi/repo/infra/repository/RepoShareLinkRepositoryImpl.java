package com.xspaceagi.repo.infra.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xspaceagi.repo.domain.model.RepoShareLinkDomain;
import com.xspaceagi.repo.domain.repository.RepoShareLinkRepository;
import com.xspaceagi.repo.infra.dao.entity.RepoShareLink;
import com.xspaceagi.repo.infra.dao.mapper.RepoShareLinkMapper;
import com.xspaceagi.repo.spec.constants.RepoConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 文档分享链接仓储实现。
 */
@Repository
@RequiredArgsConstructor
public class RepoShareLinkRepositoryImpl implements RepoShareLinkRepository {

    private final RepoShareLinkMapper repoShareLinkMapper;

    @Override
    public List<RepoShareLinkDomain> listByPageId(Long pageId) {
        return repoShareLinkMapper.selectList(new LambdaQueryWrapper<RepoShareLink>()
                        .eq(RepoShareLink::getPageId, pageId)
                        .eq(RepoShareLink::getYn, RepoConstants.YN_VALID)
                        .orderByDesc(RepoShareLink::getId))
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public RepoShareLinkDomain findByToken(String token) {
        RepoShareLink entity = repoShareLinkMapper.selectOne(new LambdaQueryWrapper<RepoShareLink>()
                .eq(RepoShareLink::getToken, token)
                .eq(RepoShareLink::getYn, RepoConstants.YN_VALID)
                .last("LIMIT 1"));
        return entity == null ? null : toDomain(entity);
    }

    @Override
    public List<RepoShareLinkDomain> listByCreatedBy(Long userId) {
        return repoShareLinkMapper.selectList(new LambdaQueryWrapper<RepoShareLink>()
                        .eq(RepoShareLink::getCreatedBy, userId)
                        .eq(RepoShareLink::getYn, RepoConstants.YN_VALID)
                        .orderByDesc(RepoShareLink::getCreated))
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public Long insert(RepoShareLinkDomain domain) {
        Date now = new Date();
        RepoShareLink entity = RepoShareLink.builder()
                .tenantId(domain.getTenantId())
                .pageId(domain.getPageId())
                .token(domain.getToken())
                .accessType(domain.getAccessType())
                .allowComment(domain.getAllowComment() == null ? 1 : domain.getAllowComment())
                .permBits(domain.getPermBits() == null ? RepoConstants.PERM_VIEW : domain.getPermBits())
                .createdBy(domain.getCreatedBy())
                .created(now)
                .modified(now)
                .yn(RepoConstants.YN_VALID)
                .build();
        repoShareLinkMapper.insert(entity);
        return entity.getId();
    }

    @Override
    public boolean updateAllowComment(Long id, Integer allowComment) {
        return repoShareLinkMapper.update(null, new LambdaUpdateWrapper<RepoShareLink>()
                .eq(RepoShareLink::getId, id)
                .eq(RepoShareLink::getYn, RepoConstants.YN_VALID)
                .set(RepoShareLink::getAllowComment, allowComment == null ? 1 : allowComment)
                .set(RepoShareLink::getModified, new Date())) > 0;
    }

    @Override
    public boolean softDelete(Long id) {
        return repoShareLinkMapper.update(null, new LambdaUpdateWrapper<RepoShareLink>()
                .eq(RepoShareLink::getId, id)
                .eq(RepoShareLink::getYn, RepoConstants.YN_VALID)
                .set(RepoShareLink::getYn, RepoConstants.YN_DELETED)) > 0;
    }

    @Override
    public int softDeleteByPageId(Long pageId) {
        return repoShareLinkMapper.update(null, new LambdaUpdateWrapper<RepoShareLink>()
                .eq(RepoShareLink::getPageId, pageId)
                .eq(RepoShareLink::getYn, RepoConstants.YN_VALID)
                .set(RepoShareLink::getYn, RepoConstants.YN_DELETED));
    }

    private RepoShareLinkDomain toDomain(RepoShareLink entity) {
        return RepoShareLinkDomain.builder()
                .id(entity.getId())
                .tenantId(entity.getTenantId())
                .pageId(entity.getPageId())
                .token(entity.getToken())
                .accessType(entity.getAccessType())
                .allowComment(entity.getAllowComment())
                .permBits(entity.getPermBits())
                .createdBy(entity.getCreatedBy())
                .created(entity.getCreated())
                .modified(entity.getModified())
                .build();
    }
}
