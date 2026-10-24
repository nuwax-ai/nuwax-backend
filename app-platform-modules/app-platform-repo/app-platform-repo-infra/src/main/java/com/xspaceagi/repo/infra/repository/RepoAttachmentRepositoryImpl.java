package com.xspaceagi.repo.infra.repository;

import com.xspaceagi.repo.domain.model.RepoAttachmentDomain;
import com.xspaceagi.repo.domain.repository.RepoAttachmentRepository;
import com.xspaceagi.repo.infra.dao.entity.RepoAttachment;
import com.xspaceagi.repo.infra.dao.mapper.RepoAttachmentMapper;
import com.xspaceagi.repo.spec.constants.RepoConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文档附件仓储实现。
 * 租户条件由平台 TenantLineInnerInterceptor 统一注入，此处不显式处理 _tenant_id。
 */
@Repository
@RequiredArgsConstructor
public class RepoAttachmentRepositoryImpl implements RepoAttachmentRepository {

    private final RepoAttachmentMapper repoAttachmentMapper;

    @Override
    public Long insert(RepoAttachmentDomain domain) {
        RepoAttachment entity = RepoAttachment.builder()
                .tenantId(domain.getTenantId())
                .spaceId(domain.getSpaceId())
                .pageId(domain.getPageId())
                .userId(domain.getUserId())
                .fileKey(domain.getFileKey())
                .fileName(domain.getFileName())
                .mime(domain.getMime())
                .size(domain.getSize())
                .yn(RepoConstants.YN_VALID)
                .build();
        repoAttachmentMapper.insert(entity);
        return entity.getId();
    }

    @Override
    public RepoAttachmentDomain findById(Long id) {
        RepoAttachment entity = repoAttachmentMapper.selectById(id);
        if (entity == null || !Integer.valueOf(RepoConstants.YN_VALID).equals(entity.getYn())) {
            return null;
        }
        return toDomain(entity);
    }

    @Override
    public List<RepoAttachmentDomain> listByPageId(Long pageId) {
        return repoAttachmentMapper.listByPageId(pageId).stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<RepoAttachmentDomain> listBySpaceId(Long spaceId) {
        return repoAttachmentMapper.listBySpaceId(spaceId).stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public boolean softDelete(Long id) {
        return repoAttachmentMapper.softDelete(id) > 0;
    }

    @Override
    public int softDeleteByPageIds(List<Long> pageIds) {
        if (pageIds == null || pageIds.isEmpty()) {
            return 0;
        }
        return repoAttachmentMapper.softDeleteByPageIds(pageIds);
    }

    @Override
    public Map<Long, Long> sumSizeByPageIds(List<Long> pageIds) {
        Map<Long, Long> result = new HashMap<>();
        if (pageIds == null || pageIds.isEmpty()) {
            return result;
        }
        for (RepoAttachment a : repoAttachmentMapper.listSizeByPageIds(pageIds)) {
            if (a.getPageId() == null || a.getSize() == null) {
                continue;
            }
            result.merge(a.getPageId(), a.getSize(), Long::sum);
        }
        return result;
    }

    private RepoAttachmentDomain toDomain(RepoAttachment entity) {
        return RepoAttachmentDomain.builder()
                .id(entity.getId())
                .tenantId(entity.getTenantId())
                .spaceId(entity.getSpaceId())
                .pageId(entity.getPageId())
                .userId(entity.getUserId())
                .fileKey(entity.getFileKey())
                .fileName(entity.getFileName())
                .mime(entity.getMime())
                .size(entity.getSize())
                .created(entity.getCreated())
                .build();
    }
}
