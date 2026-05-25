package com.xspaceagi.pay.spec.dto;

import lombok.Data;

@Data
public class PayDeveloperAccountUserSaveRequest {

    private Long id;

    private String email;
    private String phone;
    private String realName;
    private String idCardNo;
    private String idCardFrontPhotoUrl;
    private String idCardBackPhotoUrl;
    private String bankName;
    private String branchName;
    private String bankCardNo;
}
