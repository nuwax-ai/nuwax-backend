package com.xspaceagi.system.spec.license;

import com.xspaceagi.system.spec.enums.EditionEnum;
import lombok.Data;

/**
 * 授权码校验结果：结论 + 载荷 + 一句人话原因。
 *
 * <p>全模块只读这一个对象，不再各自解密/验签（{@code AuthInterceptor} 每请求取缓存，
 * {@code EditionRpcServiceImpl} 启动时取一次）。
 */
@Data
public class LicenseResult {

    /** 校验结论 */
    private LicenseStatus status;

    /** 验签成功时的载荷；{@code ABSENT}/{@code INVALID} 时为 null */
    private LicensePayload payload;

    /** 人话原因，进日志与管理面（不含任何密钥信息） */
    private String reason;

    public static LicenseResult of(LicenseStatus status, String reason) {
        LicenseResult result = new LicenseResult();
        result.status = status;
        result.reason = reason;
        return result;
    }

    /** 授权串是否被配置（社区版为 false） */
    public boolean isPresent() {
        return status != LicenseStatus.ABSENT;
    }

    /** 是否放行：有效期内的授权，或过期但在宽限期内 */
    public boolean isUsable() {
        return status == LicenseStatus.VALID || status == LicenseStatus.GRACE;
    }

    /** 是否处于宽限期（可用，但需要告警） */
    public boolean isInGrace() {
        return status == LicenseStatus.GRACE;
    }

    /**
     * 授权码里声明的产品版本；未声明 / 认不出 / 载荷缺失时返回 null，由调用方决定回落策略。
     */
    public EditionEnum getEdition() {
        return payload == null ? null : EditionEnum.parse(payload.getEdition());
    }
}
