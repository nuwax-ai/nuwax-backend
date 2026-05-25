package com.xspaceagi.pay.spec.dto;

import java.util.Date;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayDeveloperAccountResponse {

    private Long id;
    private Long tenantId;
    /** 关联 {@code user.id} */
    private Long userId;
    private String userName;
    private String email;
    private String phone;
    private String realName;
    private String idCardNo;
    /** 身份证正面照片 URL */
    private String idCardFrontPhotoUrl;
    /** 身份证反面照片 URL */
    private String idCardBackPhotoUrl;
    private String bankName;
    private String branchName;
    private String bankCardNo;
    private Date created;
    private Date modified;
}
