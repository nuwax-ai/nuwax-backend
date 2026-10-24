package com.xspaceagi.repo.infra.repository;

import com.xspaceagi.repo.domain.model.RepoPageVersionDomain;
import com.xspaceagi.repo.domain.repository.RepoPageVersionRepository;
import com.xspaceagi.repo.infra.dao.entity.RepoPageVersion;
import com.xspaceagi.repo.infra.dao.mapper.RepoPageVersionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 文档版本仓储实现。
 * 租户条件由平台 TenantLineInnerInterceptor 统一注入，此处不显式处理 _tenant_id。
 */
@Repository
@RequiredArgsConstructor
public class RepoPageVersionRepositoryImpl implements RepoPageVersionRepository {

    private final RepoPageVersionMapper repoPageVersionMapper;

    @Override
    public List<RepoPageVersionDomain> listByPageId(Long pageId, int offset, int size) {
        return repoPageVersionMapper.listByPageId(pageId, offset, size).stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public long countByPageId(Long pageId) {
        return repoPageVersionMapper.countByPageId(pageId);
    }

    @Override
    public RepoPageVersionDomain findById(Long id) {
        RepoPageVersion entity = repoPageVersionMapper.selectFullById(id);
        return entity == null ? null : toDomain(entity);
    }

    @Override
    public Long insert(RepoPageVersionDomain domain) {
        RepoPageVersion entity = RepoPageVersion.builder()
                .tenantId(domain.getTenantId())
                .pageId(domain.getPageId())
                .ydocSnapshot(domain.getYdocSnapshot())
                .html(domain.getHtml())
                .editorId(domain.getEditorId())
                .reason(domain.getReason())
                .build();
        repoPageVersionMapper.insert(entity);
        return entity.getId();
    }

    private RepoPageVersionDomain toDomain(RepoPageVersion entity) {
        return RepoPageVersionDomain.builder()
                .id(entity.getId())
                .tenantId(entity.getTenantId())
                .pageId(entity.getPageId())
                .ydocSnapshot(entity.getYdocSnapshot())
                .html(entity.getHtml())
                .editorId(entity.getEditorId())
                .reason(entity.getReason())
                .created(entity.getCreated())
                .build();
    }
}
