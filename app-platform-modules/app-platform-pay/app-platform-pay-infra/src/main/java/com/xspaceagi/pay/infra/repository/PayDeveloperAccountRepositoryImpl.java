package com.xspaceagi.pay.infra.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xspaceagi.pay.domain.model.PayDeveloperAccountModel;
import com.xspaceagi.pay.domain.model.PayDeveloperAccountPageSlice;
import com.xspaceagi.pay.domain.repository.PayDeveloperAccountRepository;
import com.xspaceagi.pay.infra.dao.entity.PayDeveloperAccount;
import com.xspaceagi.pay.infra.dao.mapper.PayDeveloperAccountMapper;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PayDeveloperAccountRepositoryImpl implements PayDeveloperAccountRepository {

    private final PayDeveloperAccountMapper mapper;

    @Override
    public Optional<PayDeveloperAccountModel> findById(long id) {
        return Optional.ofNullable(mapper.selectById(id)).map(this::toDomain);
    }

    @Override
    public Optional<PayDeveloperAccountModel> findByTenantIdAndUserId(long tenantId, long userId) {
        return Optional.ofNullable(
                        mapper.selectOne(
                                Wrappers.lambdaQuery(PayDeveloperAccount.class)
                                        .eq(PayDeveloperAccount::getTenantId, tenantId)
                                        .eq(PayDeveloperAccount::getUserId, userId)))
                .map(this::toDomain);
    }

    @Override
    public PayDeveloperAccountModel save(PayDeveloperAccountModel model) {
        PayDeveloperAccount e = toEntity(model);
        if (e.getId() == null) {
            mapper.insert(e);
        } else {
            mapper.updateById(e);
        }
        return toDomain(mapper.selectById(e.getId()));
    }

    @Override
    public void deleteById(long id) {
        mapper.deleteById(id);
    }

    @Override
    public PayDeveloperAccountPageSlice pageByTenantAndFilters(
            long tenantId,
            Long userIdEq,
            List<Long> userIdIn,
            Date createdStart,
            Date createdEnd,
            int page,
            int pageSize) {
        Page<PayDeveloperAccount> mpPage = new Page<>(page, pageSize);
        LambdaQueryWrapper<PayDeveloperAccount> w =
                Wrappers.lambdaQuery(PayDeveloperAccount.class)
                        .eq(PayDeveloperAccount::getTenantId, tenantId)
                        .eq(userIdEq != null, PayDeveloperAccount::getUserId, userIdEq)
                        .in(userIdIn != null && !userIdIn.isEmpty(), PayDeveloperAccount::getUserId, userIdIn)
                        .ge(createdStart != null, PayDeveloperAccount::getCreated, createdStart)
                        .le(createdEnd != null, PayDeveloperAccount::getCreated, createdEnd)
                        .orderByDesc(PayDeveloperAccount::getId);
        Page<PayDeveloperAccount> out = mapper.selectPage(mpPage, w);
        return new PayDeveloperAccountPageSlice(out.getRecords().stream().map(this::toDomain).toList(), out.getTotal());
    }

    private PayDeveloperAccountModel toDomain(PayDeveloperAccount e) {
        if (e == null) {
            return null;
        }
        return PayDeveloperAccountModel.builder()
                .id(e.getId())
                .tenantId(e.getTenantId())
                .userId(e.getUserId())
                .email(e.getEmail())
                .phone(e.getPhone())
                .realName(e.getRealName())
                .idCardNo(e.getIdCardNo())
                .idCardFrontPhotoUrl(e.getIdCardFrontPhotoUrl())
                .idCardBackPhotoUrl(e.getIdCardBackPhotoUrl())
                .bankName(e.getBankName())
                .branchName(e.getBranchName())
                .bankCardNo(e.getBankCardNo())
                .created(e.getCreated())
                .modified(e.getModified())
                .build();
    }

    private PayDeveloperAccount toEntity(PayDeveloperAccountModel m) {
        PayDeveloperAccount e = new PayDeveloperAccount();
        e.setId(m.getId());
        e.setTenantId(m.getTenantId());
        e.setUserId(m.getUserId());
        e.setEmail(m.getEmail());
        e.setPhone(m.getPhone());
        e.setRealName(m.getRealName());
        e.setIdCardNo(m.getIdCardNo());
        e.setIdCardFrontPhotoUrl(m.getIdCardFrontPhotoUrl());
        e.setIdCardBackPhotoUrl(m.getIdCardBackPhotoUrl());
        e.setBankName(m.getBankName());
        e.setBranchName(m.getBranchName());
        e.setBankCardNo(m.getBankCardNo());
        e.setCreated(m.getCreated());
        e.setModified(m.getModified());
        return e;
    }
}
