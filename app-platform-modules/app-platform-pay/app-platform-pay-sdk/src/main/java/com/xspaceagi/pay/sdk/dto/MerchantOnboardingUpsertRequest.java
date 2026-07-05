package com.xspaceagi.pay.sdk.dto;

import com.xspaceagi.pay.sdk.enums.MerchantOnboardingStatus;
import com.xspaceagi.pay.sdk.enums.MerchantOnboardingType;
import lombok.Data;

/**
 * 创建/全量更新进件资料。
 * 影像优先传 {@code *FileKey}（本系统上传）；{@code *Url} 保留兼容外部链接。
 */
@Data
public class MerchantOnboardingUpsertRequest {
    private MerchantOnboardingType onboardingType;

    /** 由服务端按登录租户凭证写入，请求体传入将被忽略 */
    private String clientId;

    /** 用户进件填写 */
    private Long userId;

    /** 毫秒级 Unix 时间戳 */
    private Long timestamp;

    private String nonce;

    private String signature;

    private MerchantOnboardingStatus status;

    private String auditRemark;

    private String merchantName;
    private String merchantShortName;
    private String creditCode;
    private String registeredAddress;
    private String legalPersonName;
    private String legalPersonIdNo;

    private String contactName;
    private String contactPhone;
    private String contactEmail;

    private String bankAccountName;
    private String bankName;
    private String bankBranchName;
    private String bankAccountNo;
    private String bankReceiptRemark;
    private String remark;

    private String orgCertificateUrl;
    private String legalPersonIdCardUrl;
    /** 法人身份证人像面/正面图片 URL */
    private String legalPersonIdCardFrontUrl;
    /** 法人身份证国徽面/反面图片 URL */
    private String legalPersonIdCardBackUrl;
    private String photoFinanceRoomUrl;
    private String photoGateUrl;
    private String photoLandmarkUrl;
    private String bankAccountProofUrl;


    /** 营业执照；本系统上传时为 file_entry.file_key */
    private String orgCertificateFileKey;
    /** 法人身份证人像面/正面 file_key */
    private String legalPersonIdCardFrontFileKey;
    /** 法人身份证国徽面/反面 file_key */
    private String legalPersonIdCardBackFileKey;
    private String photoFinanceRoomFileKey;
    private String photoGateFileKey;
    private String photoLandmarkFileKey;
    private String bankAccountProofFileKey;
}
