package com.xspaceagi.pay.infra.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.Date;
import lombok.Data;

@Data
@TableName("pay_developer_account")
public class PayDeveloperAccount {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("_tenant_id")
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
