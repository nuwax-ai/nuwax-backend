package com.xspaceagi.system.infra.idp;

import lombok.Data;

import java.util.Map;

/**
 * CAS 类型配置
 */
@Data
public class CasIdpConfig {

    /**
     * CAS 服务地址
     */
    private String serverUrl;

    /**
     * 字段映射：平台字段 ← CAS 返回属性名。
     * key 取值 userName / nickName / email / phone
     */
    private Map<String, String> fieldMapping;
}
