package com.xspaceagi.pay.application.service.support;

import com.xspaceagi.pay.domain.model.PayDeveloperAccountModel;
import com.xspaceagi.pay.spec.dto.PayDeveloperAccountAdminSaveRequest;
import com.xspaceagi.pay.spec.dto.PayDeveloperAccountResponse;
import com.xspaceagi.pay.spec.dto.PayDeveloperAccountUserSaveRequest;
import java.util.Map;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PayDeveloperAccountAssembler {

    public static void applyUserSave(PayDeveloperAccountUserSaveRequest request, PayDeveloperAccountModel row) {
        row.setEmail(request.getEmail());
        row.setPhone(request.getPhone());
        row.setRealName(request.getRealName());
        row.setIdCardNo(request.getIdCardNo());
        row.setIdCardFrontPhotoUrl(request.getIdCardFrontPhotoUrl());
        row.setIdCardBackPhotoUrl(request.getIdCardBackPhotoUrl());
        row.setBankName(request.getBankName());
        row.setBranchName(request.getBranchName());
        row.setBankCardNo(request.getBankCardNo());
    }

    public static void applyAdminSave(PayDeveloperAccountAdminSaveRequest request, PayDeveloperAccountModel row) {
        row.setEmail(request.getEmail());
        row.setPhone(request.getPhone());
        row.setRealName(request.getRealName());
        row.setIdCardNo(request.getIdCardNo());
        row.setIdCardFrontPhotoUrl(request.getIdCardFrontPhotoUrl());
        row.setIdCardBackPhotoUrl(request.getIdCardBackPhotoUrl());
        row.setBankName(request.getBankName());
        row.setBranchName(request.getBranchName());
        row.setBankCardNo(request.getBankCardNo());
    }

    public static PayDeveloperAccountResponse toResponse(PayDeveloperAccountModel m) {
        return PayDeveloperAccountResponse.builder()
                .id(m.getId())
                .tenantId(m.getTenantId())
                .userId(m.getUserId())
                .email(m.getEmail())
                .phone(m.getPhone())
                .realName(m.getRealName())
                .idCardNo(m.getIdCardNo())
                .idCardFrontPhotoUrl(m.getIdCardFrontPhotoUrl())
                .idCardBackPhotoUrl(m.getIdCardBackPhotoUrl())
                .bankName(m.getBankName())
                .branchName(m.getBranchName())
                .bankCardNo(m.getBankCardNo())
                .created(m.getCreated())
                .modified(m.getModified())
                .build();
    }

    public static PayDeveloperAccountResponse toResponse(PayDeveloperAccountModel m, Map<Long, String> userIdToUserName) {
        PayDeveloperAccountResponse r = toResponse(m);
        if (m.getUserId() != null && userIdToUserName != null) {
            r.setUserName(userIdToUserName.get(m.getUserId()));
        }
        return r;
    }
}
