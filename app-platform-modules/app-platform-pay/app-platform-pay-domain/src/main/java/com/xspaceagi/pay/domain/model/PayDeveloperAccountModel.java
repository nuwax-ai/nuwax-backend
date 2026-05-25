package com.xspaceagi.pay.domain.model;

import java.util.Date;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayDeveloperAccountModel {

    private Long id;
    private Long tenantId;
    private Long userId;
    private String email;
    private String phone;
    private String realName;
    private String idCardNo;
    private String idCardFrontPhotoUrl;
    private String idCardBackPhotoUrl;
    private String bankName;
    private String branchName;
    private String bankCardNo;
    private Date created;
    private Date modified;
}
