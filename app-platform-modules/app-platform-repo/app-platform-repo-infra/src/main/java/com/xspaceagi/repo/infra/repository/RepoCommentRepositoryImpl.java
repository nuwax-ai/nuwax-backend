package com.xspaceagi.repo.infra.repository;

import com.xspaceagi.repo.domain.model.RepoCommentDomain;
import com.xspaceagi.repo.domain.repository.RepoCommentRepository;
import com.xspaceagi.repo.infra.dao.entity.RepoComment;
import com.xspaceagi.repo.infra.dao.mapper.RepoCommentMapper;
import com.xspaceagi.repo.spec.constants.RepoConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 文档评论仓储实现。
 * 租户条件由平台 TenantLineInnerInterceptor 统一注入，此处不显式处理 _tenant_id。
 */
@Repository
@RequiredArgsConstructor
public class RepoCommentRepositoryImpl implements RepoCommentRepository {

    private final RepoCommentMapper repoCommentMapper;

    @Override
    public Long insert(RepoCommentDomain domain) {
        RepoComment entity = RepoComment.builder()
                .tenantId(domain.getTenantId())
                .pageId(domain.getPageId())
                .userId(domain.getUserId())
                .parentId(domain.getParentId())
                .content(domain.getContent())
                .mentions(domain.getMentions())
                .anchor(domain.getAnchor())
                .resolved(RepoConstants.COMMENT_UNRESOLVED)
                .yn(RepoConstants.YN_VALID)
                .build();
        repoCommentMapper.insert(entity);
        return entity.getId();
    }

    @Override
    public RepoCommentDomain findById(Long id) {
        RepoComment entity = repoCommentMapper.selectById(id);
        if (entity == null || !Integer.valueOf(RepoConstants.YN_VALID).equals(entity.getYn())) {
            return null;
        }
        return toDomain(entity);
    }

    @Override
    public List<RepoCommentDomain> listByPageId(Long pageId) {
        return repoCommentMapper.listByPageId(pageId).stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public boolean updateResolved(Long id, int resolved) {
        return repoCommentMapper.updateResolved(id, resolved) > 0;
    }

    @Override
    public int softDeleteByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        return repoCommentMapper.softDeleteByIds(ids);
    }

    private RepoCommentDomain toDomain(RepoComment entity) {
        return RepoCommentDomain.builder()
                .id(entity.getId())
                .tenantId(entity.getTenantId())
                .pageId(entity.getPageId())
                .userId(entity.getUserId())
                .parentId(entity.getParentId())
                .content(entity.getContent())
                .mentions(entity.getMentions())
                .anchor(entity.getAnchor())
                .resolved(entity.getResolved())
                .created(entity.getCreated())
                .modified(entity.getModified())
                .build();
    }
}
