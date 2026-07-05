package com.xspaceagi.pay.sdk.dto;

import com.xspaceagi.pay.sdk.enums.MerchantOnboardingStatus;
import com.xspaceagi.pay.sdk.enums.MerchantOnboardingType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class MerchantOnboardingResponse {
    private Long id;
    private MerchantOnboardingType onboardingType;
    private String clientId;
    private Long userId;
    private MerchantOnboardingStatus status;
    private String auditRemark;
    /** 审核时间 */
    private LocalDateTime auditedAt;

    /** 商户名称 */
    private String merchantName;
    /** 商户简称 */
    private String merchantShortName;
    /** 统一社会信用代码 */
    private String creditCode;
    /** 注册地址 */
    private String registeredAddress;
    /** 法人姓名 */
    private String legalPersonName;
    /** 法人证件号 */
    private String legalPersonIdNo;

    /** 经办联系人 */
    private String contactName;
    /** 联系人电话 */
    private String contactPhone;
    /** 联系人邮箱 */
    private String contactEmail;

    /** 开户名称 */
    private String bankName;
    /** 开户银行名称 */
    private String bankAccountName;
    /** 开户银行支行名称 */
    private String bankBranchName;
    /** 银行账号 */
    private String bankAccountNo;
    /** 银行回单附言备注 */
    private String bankReceiptRemark;
    /** 备注 */
    private String remark;

    /** 营业执照 */
    private String orgCertificateUrl;
    private String orgCertificateFileKey;
    /** 法人身份证正面 */
    private String legalPersonIdCardFrontUrl;
    private String legalPersonIdCardFrontFileKey;
    /** 法人身份证背面 */
    private String legalPersonIdCardBackUrl;
    private String legalPersonIdCardBackFileKey;
    /** 财务室照片 */
    private String photoFinanceRoomUrl;
    private String photoFinanceRoomFileKey;
    /** 门头照片 */
    private String photoGateUrl;
    private String photoGateFileKey;
    /** 地标照片 */
    private String photoLandmarkUrl;
    private String photoLandmarkFileKey;
    /** 银行开户证明 */
    private String bankAccountProofUrl;
    private String bankAccountProofFileKey;

    private LocalDateTime created;
    private LocalDateTime modified;
}
