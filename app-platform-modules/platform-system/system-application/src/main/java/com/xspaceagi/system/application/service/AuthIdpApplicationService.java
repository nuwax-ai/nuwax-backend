package com.xspaceagi.system.application.service;

import com.xspaceagi.system.application.dto.AuthIdpCallbackResult;
import com.xspaceagi.system.application.dto.AuthIdpLoginListVo;
import com.xspaceagi.system.application.dto.AuthIdpLoginResult;
import com.xspaceagi.system.application.dto.AuthIdpPendingVo;

/**
 * 外部登录（CAS / 标准 OAuth2 / 微信）统一编排：
 * 发起授权（写 state）→ 回跳校验 → 身份查找链 → 发平台 token 或进入绑定/注册中间页
 */
public interface AuthIdpApplicationService {

    /**
     * 登录页可用登录方式列表（当前租户已启用项 + 未登录自动跳转目标）
     */
    AuthIdpLoginListVo listForLogin();

    /**
     * 自动跳转 IdP 的 authorize 地址；未配置自动跳转时返回 null（调用方自行回落）。
     * redirect 为登录完成后目标（本站相对路径，可空，空取 /）
     */
    String buildAutoAuthorizeUrl(String redirect);

    /**
     * 未登录跳转目标决策（AuthInterceptor 4011 用）：租户配置了「未登录自动跳转」的启用登录方式时，
     * 返回该 IdP 的 authorize 地址（redirect 携带原目标）；否则返回默认登录页地址。
     * 原目标含 local=1 时强制走默认登录页（逃生门）；决策异常回落登录页不影响主流程。
     */
    String resolveUnauthRedirectUrl(String defaultLoginUrl, String originalTarget);

    /**
     * 发起授权：校验配置、生成 state 写 Redis，返回跳转 IdP 的完整 URL。
     * bindMode=true 为已登录用户绑定身份（须在登录态调用），state 记录发起用户。
     *
     * @param redirect 登录完成后的回跳目标（同站相对路径或本站绝对地址，越权值会被重置为 "/"）
     */
    String buildAuthorizeUrl(Long providerId, String redirect, boolean bindMode);

    /**
     * IdP 回跳处理：校验 state（一次性、租户绑定）→ 换取外部身份 → 查找绑定关系。
     * 命中：签发平台 token（LOGIN）或完成绑定（BIND）；未命中：生成 pending token 进中间页（PENDING）
     */
    AuthIdpCallbackResult handleCallback(Long providerId, String code, String ticket, String state);

    /**
     * 中间页读取待处理外部身份（不消费，绑定/注册成功才失效）
     */
    AuthIdpPendingVo getPendingInfo(String pendingToken);

    /**
     * 中间页 注册并登录：用外部身份自动建号并登录（受租户 openRegister 开关约束）
     */
    AuthIdpLoginResult registerPending(String pendingToken);

    /**
     * 中间页 绑定已有账号：把外部身份绑到当前登录用户（须在登录态调用），
     * 返回登录完成后的回跳目标
     */
    String bindPending(String pendingToken);

    /**
     * 当前会话的 IdP 联动登出地址（登出端点用）：会话来自 CAS 型登录方式时返回
     * {serverUrl}/cas/logout?service={站点}/login?local=1，用于平台登出后清 IdP 的 TGT，
     * 否则返回 null（密码登录/微信/OAuth2 会话只需登出平台本身）
     */
    String buildSessionIdpLogoutUrl();
}
