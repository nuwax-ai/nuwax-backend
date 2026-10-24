package com.xspaceagi.userapp.domain.gateway;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 生产部署/停止/重启请求参数（领域层命令，infra 层负责映射为下游 snake_case 请求体）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserAppDeployCommand {

    /** 发布版本 */
    private String releaseId;

    /** 构建产物压缩包地址 */
    private String packageUrl;

    /** 生产环境数据库账号 */
    private String dbUsername;

    /** 生产环境数据库密码（明文，仅出参到下游，不落日志） */
    private String dbPassword;

    /** 应用所有者用户ID */
    private Long userId;

    /** 生产闲置回收超时(秒)，取创建者数据权限；null/-1=不限制（不下发，沿用下游默认），0=不回收（常驻） */
    private Integer idleTimeoutSeconds;

    /** 是否自动执行 SQL 脚本 */
    @Builder.Default
    private Boolean autoExecuteSql = Boolean.TRUE;
}
