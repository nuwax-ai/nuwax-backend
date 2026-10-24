package com.xspaceagi.repo.infra.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xspaceagi.repo.domain.model.RepoPageAclDomain;
import com.xspaceagi.repo.domain.repository.RepoPageAclRepository;
import com.xspaceagi.repo.infra.dao.entity.RepoPageAcl;
import com.xspaceagi.repo.infra.dao.mapper.RepoPageAclMapper;
import com.xspaceagi.repo.spec.constants.RepoConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 文档级按人权限仓储实现。
 */
@Repository
@RequiredArgsConstructor
public class RepoPageAclRepositoryImpl implements RepoPageAclRepository {

    private final RepoPageAclMapper repoPageAclMapper;

    @Override
    public List<RepoPageAclDomain> listByPageId(Long pageId) {
        return repoPageAclMapper.selectList(new LambdaQueryWrapper<RepoPageAcl>()
                        .eq(RepoPageAcl::getPageId, pageId)
                        .eq(RepoPageAcl::getYn, RepoConstants.YN_VALID)
                        .orderByAsc(RepoPageAcl::getId))
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public RepoPageAclDomain findByPageIdAndUserId(Long pageId, Long userId) {
        RepoPageAcl entity = repoPageAclMapper.selectOne(new LambdaQueryWrapper<RepoPageAcl>()
                .eq(RepoPageAcl::getPageId, pageId)
                .eq(RepoPageAcl::getUserId, userId)
                .eq(RepoPageAcl::getYn, RepoConstants.YN_VALID)
                .last("LIMIT 1"));
        return entity == null ? null : toDomain(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceAll(Long pageId, List<RepoPageAclDomain> entries) {
        // 全量覆盖：物理删除该页面全部条目（含历史软删行，唯一键 (page,user,yn) 下
        // 若用软删，反复覆盖会撞重复键），然后插入新条目
        repoPageAclMapper.delete(new LambdaQueryWrapper<RepoPageAcl>()
                .eq(RepoPageAcl::getPageId, pageId));
        if (entries == null || entries.isEmpty()) {
            return;
        }
        Date now = new Date();
        for (RepoPageAclDomain entry : entries) {
            RepoPageAcl entity = RepoPageAcl.builder()
                    .tenantId(entry.getTenantId())
                    .pageId(pageId)
                    .userId(entry.getUserId())
                    .permBits(entry.getPermBits())
                    .created(now)
                    .modified(now)
                    .yn(RepoConstants.YN_VALID)
                    .build();
            repoPageAclMapper.insert(entity);
        }
    }

    private RepoPageAclDomain toDomain(RepoPageAcl entity) {
        return RepoPageAclDomain.builder()
                .id(entity.getId())
                .tenantId(entity.getTenantId())
                .pageId(entity.getPageId())
                .userId(entity.getUserId())
                .permBits(entity.getPermBits())
                .created(entity.getCreated())
                .modified(entity.getModified())
                .build();
    }
}
