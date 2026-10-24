package com.xspaceagi.repo.infra.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xspaceagi.repo.domain.model.RepoShareUserDomain;
import com.xspaceagi.repo.domain.repository.RepoShareUserRepository;
import com.xspaceagi.repo.infra.dao.entity.RepoShareUser;
import com.xspaceagi.repo.infra.dao.mapper.RepoShareUserMapper;
import com.xspaceagi.repo.spec.constants.RepoConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 文档个人分享仓储实现（需求3）。
 */
@Repository
@RequiredArgsConstructor
public class RepoShareUserRepositoryImpl implements RepoShareUserRepository {

    private final RepoShareUserMapper repoShareUserMapper;

    @Override
    public List<RepoShareUserDomain> listByPageId(Long pageId) {
        return repoShareUserMapper.selectList(new LambdaQueryWrapper<RepoShareUser>()
                        .eq(RepoShareUser::getPageId, pageId)
                        .eq(RepoShareUser::getYn, RepoConstants.YN_VALID)
                        .orderByAsc(RepoShareUser::getId))
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<RepoShareUserDomain> listManualByPageId(Long pageId) {
        return repoShareUserMapper.selectList(new LambdaQueryWrapper<RepoShareUser>()
                        .eq(RepoShareUser::getPageId, pageId)
                        .eq(RepoShareUser::getYn, RepoConstants.YN_VALID)
                        .isNull(RepoShareUser::getLinkId)
                        .orderByAsc(RepoShareUser::getId))
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<RepoShareUserDomain> listByPageIdAndTargetUserId(Long pageId, Long targetUserId) {
        return repoShareUserMapper.selectList(new LambdaQueryWrapper<RepoShareUser>()
                        .eq(RepoShareUser::getPageId, pageId)
                        .eq(RepoShareUser::getTargetUserId, targetUserId)
                        .eq(RepoShareUser::getYn, RepoConstants.YN_VALID))
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<RepoShareUserDomain> listByTargetUserId(Long targetUserId) {
        return repoShareUserMapper.selectList(new LambdaQueryWrapper<RepoShareUser>()
                        .eq(RepoShareUser::getTargetUserId, targetUserId)
                        .eq(RepoShareUser::getYn, RepoConstants.YN_VALID)
                        .orderByDesc(RepoShareUser::getCreated))
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public RepoShareUserDomain findByPageIdAndTargetUserIdIncludeDeleted(Long pageId, Long targetUserId) {
        RepoShareUser entity = repoShareUserMapper.selectOne(new LambdaQueryWrapper<RepoShareUser>()
                .eq(RepoShareUser::getPageId, pageId)
                .eq(RepoShareUser::getTargetUserId, targetUserId)
                .orderByDesc(RepoShareUser::getId)
                .last("LIMIT 1"));
        return entity == null ? null : toDomain(entity);
    }

    @Override
    public boolean revive(Long id, Integer permBits, Long createdBy, Long linkId) {
        Date now = new Date();
        LambdaUpdateWrapper<RepoShareUser> wrapper = new LambdaUpdateWrapper<RepoShareUser>()
                .eq(RepoShareUser::getId, id)
                .set(RepoShareUser::getYn, RepoConstants.YN_VALID)
                .set(RepoShareUser::getPermBits, permBits)
                .set(RepoShareUser::getCreatedBy, createdBy)
                .set(RepoShareUser::getCreated, now)
                .set(RepoShareUser::getModified, now);
        setLinkId(wrapper, linkId);
        return repoShareUserMapper.update(null, wrapper) > 0;
    }

    @Override
    public Long insert(RepoShareUserDomain domain) {
        Date now = new Date();
        RepoShareUser entity = RepoShareUser.builder()
                .tenantId(domain.getTenantId())
                .pageId(domain.getPageId())
                .targetUserId(domain.getTargetUserId())
                .permBits(domain.getPermBits())
                .createdBy(domain.getCreatedBy())
                .linkId(domain.getLinkId())
                .created(now)
                .modified(now)
                .yn(RepoConstants.YN_VALID)
                .build();
        repoShareUserMapper.insert(entity);
        return entity.getId();
    }

    @Override
    public boolean updatePermBits(Long id, Integer permBits, Long linkId) {
        LambdaUpdateWrapper<RepoShareUser> wrapper = new LambdaUpdateWrapper<RepoShareUser>()
                .eq(RepoShareUser::getId, id)
                .eq(RepoShareUser::getYn, RepoConstants.YN_VALID)
                .set(RepoShareUser::getPermBits, permBits);
        setLinkId(wrapper, linkId);
        return repoShareUserMapper.update(null, wrapper) > 0;
    }

    @Override
    public boolean softDelete(Long id) {
        return repoShareUserMapper.update(null, new LambdaUpdateWrapper<RepoShareUser>()
                .eq(RepoShareUser::getId, id)
                .eq(RepoShareUser::getYn, RepoConstants.YN_VALID)
                .set(RepoShareUser::getYn, RepoConstants.YN_DELETED)) > 0;
    }

    @Override
    public int softDeleteByPageId(Long pageId) {
        return repoShareUserMapper.update(null, new LambdaUpdateWrapper<RepoShareUser>()
                .eq(RepoShareUser::getPageId, pageId)
                .eq(RepoShareUser::getYn, RepoConstants.YN_VALID)
                .set(RepoShareUser::getYn, RepoConstants.YN_DELETED));
    }

    @Override
    public int softDeleteByLinkId(Long linkId) {
        if (linkId == null) {
            return 0;
        }
        return repoShareUserMapper.update(null, new LambdaUpdateWrapper<RepoShareUser>()
                .eq(RepoShareUser::getLinkId, linkId)
                .eq(RepoShareUser::getYn, RepoConstants.YN_VALID)
                .set(RepoShareUser::getYn, RepoConstants.YN_DELETED));
    }

    /**
     * 设置链接来源列。linkId 为 NULL 时需显式清空（MyBatis-Plus 默认 NOT_NULL 策略会跳过
     * null 值，无法把已有 link_id 覆盖为 NULL），故用 setSql 兜底。
     */
    private void setLinkId(LambdaUpdateWrapper<RepoShareUser> wrapper, Long linkId) {
        if (linkId != null) {
            wrapper.set(RepoShareUser::getLinkId, linkId);
        } else {
            wrapper.setSql("link_id = NULL");
        }
    }

    private RepoShareUserDomain toDomain(RepoShareUser entity) {
        return RepoShareUserDomain.builder()
                .id(entity.getId())
                .tenantId(entity.getTenantId())
                .pageId(entity.getPageId())
                .targetUserId(entity.getTargetUserId())
                .permBits(entity.getPermBits())
                .createdBy(entity.getCreatedBy())
                .linkId(entity.getLinkId())
                .yn(entity.getYn())
                .created(entity.getCreated())
                .modified(entity.getModified())
                .build();
    }
}
