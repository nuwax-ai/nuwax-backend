package com.xspaceagi.repo.infra.repository;

import cn.hutool.json.JSONUtil;
import com.xspaceagi.repo.domain.model.RepoPageDomain;
import com.xspaceagi.repo.domain.repository.RepoPageRepository;
import com.xspaceagi.repo.infra.dao.entity.RepoPage;
import com.xspaceagi.repo.infra.dao.mapper.RepoPageMapper;
import com.xspaceagi.repo.spec.constants.RepoConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 文档页面仓储实现。
 * 租户条件由平台 TenantLineInnerInterceptor 统一注入，此处不显式处理 _tenant_id。
 */
@Repository
@RequiredArgsConstructor
public class RepoPageRepositoryImpl implements RepoPageRepository {

    private final RepoPageMapper repoPageMapper;

    @Override
    public RepoPageDomain findById(Long id) {
        RepoPage entity = repoPageMapper.selectById(id);
        if (entity == null || !Integer.valueOf(RepoConstants.YN_VALID).equals(entity.getYn())) {
            return null;
        }
        return toDomain(entity);
    }

    @Override
    public RepoPageDomain findBySlugId(String slugId) {
        RepoPage entity = repoPageMapper.findBySlugId(slugId);
        return entity == null ? null : toDomain(entity);
    }

    @Override
    public List<RepoPageDomain> listRootsBySpaceId(Long spaceId) {
        return repoPageMapper.listRootsBySpaceId(spaceId).stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<RepoPageDomain> listChildren(Long spaceId, Long parentId) {
        return repoPageMapper.listChildren(spaceId, parentId).stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<RepoPageDomain> listBySpaceId(Long spaceId) {
        return repoPageMapper.listBySpaceId(spaceId).stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public Long insert(RepoPageDomain domain) {
        RepoPage entity = toEntity(domain);
        entity.setYn(RepoConstants.YN_VALID);
        repoPageMapper.insert(entity);
        return entity.getId();
    }

    @Override
    public boolean updateTitle(Long id, String title) {
        return repoPageMapper.updateTitle(id, title) > 0;
    }

    @Override
    public boolean updateParentAndPosition(Long id, Long parentId, String position) {
        return repoPageMapper.updateParentAndPosition(id, parentId, position) > 0;
    }

    @Override
    public int softDeleteByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        return repoPageMapper.softDeleteByIds(ids);
    }

    @Override
    public boolean updateSnapshot(Long id, String contentSnapshot, String htmlSnapshot, Long mediaSize, Long lastEditorId, java.util.Date editedAt, String editorIdsJson) {
        return repoPageMapper.updateSnapshot(id, contentSnapshot, htmlSnapshot, mediaSize, lastEditorId, editedAt, editorIdsJson) > 0;
    }

    @Override
    public boolean updateMediaSize(Long id, Long mediaSize) {
        return repoPageMapper.updateMediaSize(id, mediaSize) > 0;
    }

    @Override
    public List<Long> listIdsForMediaBackfill() {
        return repoPageMapper.listIdsForMediaBackfill();
    }

    /**
     * 单条查询判定的 key 上限。每个 key 展开成一个 SUM(CASE WHEN ...) 列，
     * 太宽的 SELECT 既难读也易触发 SQL 长度限制，故分批。
     */
    private static final int REFERENCE_CHECK_BATCH = 50;

    @Override
    public Set<String> filterReferencedFileKeys(Collection<String> fileKeys, List<Long> excludeIds) {
        Set<String> referenced = new HashSet<>();
        if (fileKeys == null || fileKeys.isEmpty() || excludeIds == null || excludeIds.isEmpty()) {
            return referenced;
        }
        List<String> keys = fileKeys.stream()
                .filter(k -> k != null && !k.isBlank())
                .distinct()
                .collect(Collectors.toList());
        for (int start = 0; start < keys.size(); start += REFERENCE_CHECK_BATCH) {
            List<String> batch = keys.subList(start, Math.min(start + REFERENCE_CHECK_BATCH, keys.size()));
            Map<String, Object> row = repoPageMapper.countReferencesForFileKeys(batch, excludeIds);
            if (row == null || row.isEmpty()) {
                continue;
            }
            // 列名大小写取决于驱动，统一按小写比对
            Map<String, Object> normalized = new HashMap<>();
            row.forEach((k, v) -> normalized.put(k == null ? "" : k.toLowerCase(), v));
            for (int i = 0; i < batch.size(); i++) {
                Object count = normalized.get("k" + i);
                if (count instanceof Number && ((Number) count).longValue() > 0) {
                    referenced.add(batch.get(i));
                }
            }
        }
        return referenced;
    }

    @Override
    public boolean updateAclEnabled(Long id, Integer aclEnabled) {
        return repoPageMapper.updateAclEnabled(id, aclEnabled) > 0;
    }

    @Override
    public List<RepoPageDomain> listRecent(List<Long> spaceIds, int size) {
        if (spaceIds == null || spaceIds.isEmpty() || size <= 0) {
            return new ArrayList<>();
        }
        return repoPageMapper.listRecent(spaceIds, size).stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<RepoPageDomain> listByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }
        return repoPageMapper.listByIds(ids).stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<RepoPageDomain> listRecentByEditor(Long userId, int size) {
        if (userId == null || size <= 0) {
            return new ArrayList<>();
        }
        return repoPageMapper.listRecentByEditor(userId, size).stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<RepoPageDomain> listBySpaceIds(List<Long> spaceIds, int from, int size) {
        if (spaceIds == null || spaceIds.isEmpty() || size <= 0) {
            return new ArrayList<>();
        }
        return repoPageMapper.listBySpaceIds(spaceIds, from, size).stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public byte[] findYdocStateById(Long id) {
        // mapper 以实体承载 ydoc_state 单列：byte[] 返回类型会被 MyBatis 按多行查询转换，抛 argument type mismatch
        RepoPage row = repoPageMapper.selectYdocStateById(id);
        return row == null ? null : row.getYdocState();
    }

    @Override
    public boolean updateYdocState(Long id, byte[] ydocState, Long lastEditorId, java.util.Date editedAt) {
        return repoPageMapper.updateYdocState(id, ydocState, lastEditorId, editedAt) > 0;
    }

    private RepoPageDomain toDomain(RepoPage entity) {
        return RepoPageDomain.builder()
                .id(entity.getId())
                .tenantId(entity.getTenantId())
                .spaceId(entity.getSpaceId())
                .parentId(entity.getParentId())
                .title(entity.getTitle())
                .slugId(entity.getSlugId())
                .position(entity.getPosition())
                .pageType(entity.getPageType())
                .contentSnapshot(entity.getContentSnapshot())
                .htmlSnapshot(entity.getHtmlSnapshot())
                .mediaSize(entity.getMediaSize())
                .contentSize(entity.getContentSize())
                .creatorId(entity.getCreatorId())
                .lastEditorId(entity.getLastEditorId())
                .editedAt(entity.getEditedAt())
                .sourceExt(entity.getSourceExt())
                .originalFileKey(entity.getOriginalFileKey())
                .editorIds(parseEditorIds(entity.getEditorIds()))
                .aclEnabled(entity.getAclEnabled() == null ? 0 : entity.getAclEnabled())
                .created(entity.getCreated())
                .modified(entity.getModified())
                .build();
    }

    /**
     * editor_ids JSON数组 → List；空值/非法 JSON 返回空列表（容错，不阻断读取）
     */
    private List<Long> parseEditorIds(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return JSONUtil.toList(json, Long.class);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private RepoPage toEntity(RepoPageDomain domain) {
        return RepoPage.builder()
                .id(domain.getId())
                .tenantId(domain.getTenantId())
                .spaceId(domain.getSpaceId())
                .parentId(domain.getParentId())
                .title(domain.getTitle())
                .slugId(domain.getSlugId())
                .position(domain.getPosition())
                .pageType(domain.getPageType())
                .contentSnapshot(domain.getContentSnapshot())
                .htmlSnapshot(domain.getHtmlSnapshot())
                .mediaSize(domain.getMediaSize())
                .creatorId(domain.getCreatorId())
                .lastEditorId(domain.getLastEditorId())
                .editedAt(domain.getEditedAt())
                .sourceExt(domain.getSourceExt())
                .originalFileKey(domain.getOriginalFileKey())
                .editorIds(domain.getEditorIds() == null || domain.getEditorIds().isEmpty()
                        ? null : JSONUtil.toJsonStr(domain.getEditorIds()))
                .aclEnabled(domain.getAclEnabled() == null ? 0 : domain.getAclEnabled())
                .build();
    }
}
