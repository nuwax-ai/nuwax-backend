package com.xspaceagi.system.sdk.service;

import com.xspaceagi.system.spec.enums.EditionEnum;
import com.xspaceagi.system.spec.license.LicenseResult;

/**
 * 授权码（License）读取契约：启动时校验一次并缓存，运行期只读缓存。
 *
 * <p>调用方按需取用：
 * <ul>
 *   <li>{@link #getResult()} —— 完整结论（分档状态 + 载荷 + 人话原因），管控策略由调用方定</li>
 *   <li>{@link #getLicensedEdition()} —— 授权码里声明的产品版本，没有则返回 null</li>
 * </ul>
 *
 * <p>本接口只回答「授权是什么」，不回答「该不该放行」——放行策略留在各自的业务入口，
 * 避免把产品决策埋进一个公共 bean 里。
 */
public interface ILicenseRpcService {

    /**
     * 当前授权码的校验结果；未配置授权串时返回 {@code LicenseStatus.ABSENT} 的结果而非 null
     */
    LicenseResult getResult(String licenseContent);

    /**
     * 授权串是否已配置（社区版为 false）
     */
    default boolean isPresent(String licenseContent) {
        return getResult(licenseContent).isPresent();
    }

    /**
     * 授权是否可用（有效期内，或过期但在宽限期内）
     */
    default boolean isUsable(String licenseContent) {
        return getResult(licenseContent).isUsable();
    }

    /**
     * 授权码里声明的产品版本（{@code aios} / {@code aisp}）。
     *
     * <p>返回 null 的三种情况：未配置授权串、授权码未声明 edition、载荷验签没通过。
     * 调用方拿到 null 时应回落到配置项 {@code app.edition}。
     */
    default EditionEnum getLicensedEdition(String licenseContent) {
        return getResult(licenseContent).getEdition();
    }
}
