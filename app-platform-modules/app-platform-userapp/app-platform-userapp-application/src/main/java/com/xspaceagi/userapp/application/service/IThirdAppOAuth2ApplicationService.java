package com.xspaceagi.userapp.application.service;

import java.util.List;

import com.xspaceagi.userapp.application.oauth2.ThirdAppOAuthCredential;
import com.xspaceagi.userapp.application.oauth2.ThirdAppOAuthSettingResult;
import com.xspaceagi.userapp.domain.model.UserProjectModel;
import com.xspaceagi.userapp.sdk.dto.RedirectUriEntry;

/**
 * 三方应用 OAuth2 接入配置（user_project 作为接入应用配置行）。
 * <p>
 * 只负责配置与凭证本身；协议侧（授权码 / 令牌）见 {@link IOAuth2AuthorizationService}。
 */
public interface IThirdAppOAuth2ApplicationService {

    /** 按统一项目 id 取三方应用配置行；不存在或类型不是三方应用直接报错 */
    UserProjectModel requireThirdApp(Long projectId);

    /** 按 client_id 取启用中的接入应用；不存在 / 类型不符 / 已停用返回 null */
    UserProjectModel findEnabledClient(String clientId);

    /** 同 {@link #findEnabledClient(String)}，但取不到时抛 oauth2ClientNotFound */
    UserProjectModel requireEnabledClient(String clientId);

    /** 常量时间比较客户端密钥，不匹配抛 oauth2ClientSecretInvalid */
    void verifyClientSecret(UserProjectModel project, String rawSecret);

    /** 解密返回 Client Secret 明文（调用方需记录审计日志） */
    String revealClientSecret(UserProjectModel project);

    /** 接入应用登记的 scope（未配置取平台默认） */
    List<String> resolveScopes(UserProjectModel project);

    /** 校验申请 scope 在接入应用允许范围内，返回实际授予的 scope */
    List<String> resolveRequestedScopes(UserProjectModel project, String requestedScope);

    /**
     * 校验回调地址命中登记值（主地址 redirect_uri 与补充列表 redirect_uris 的并集，全等匹配）。
     * <p>
     * 生效集合为空抛 oauth2RedirectUriRequired，不一致抛 oauth2RedirectUriMismatch。
     * ★判据是「生效集合为空」而不是「主地址为空」：沙箱里的 AI 只往补充列表写，
     * 主地址为空 + 补充列表有值是合法状态。
     */
    void verifyRedirectUri(UserProjectModel project, String redirectUri);

    /** 保证接入应用已有凭证（原型「平台已自动生成凭证」语义）：缺失则生成并落库 */
    UserProjectModel ensureCredential(Long projectId);

    /**
     * 保存主页地址 / 主回调地址，对补充回调地址做<b>显式增删</b>，并提交 scope 变更申请。
     * <p>
     * 字符串参数留空（null 或空串）表示不修改该项。scope 变更不直接生效：审核通过后才写回 oauth_scope。
     * <p>
     * 补充回调地址刻意<b>不做整表替换</b>：它是网页人工配置与沙箱 AI 共写的一列，整表替换会让任何一方
     * 的提交把对方的条目一并抹掉。改成 {@code addRedirectUris} / {@code removeRedirectUris} 两个动作后，
     * 一次提交只影响点名的那些地址。
     * <p>
     * 归属规则（服务端强制，防不住调用方绕过前端）：
     * <ul>
     *   <li>{@code source == Sandbox}（沙箱里的 AI）：新增条目打上 Sandbox 标记；删除时<b>只能删
     *       Sandbox 条目</b>，删到人工条目一律跳过并回报在
     *       {@link ThirdAppOAuthSettingResult#skippedRedirectUriRemovals()}（不抛错，见该 record 的说明）；
     *       主地址 {@code redirectUri} <b>一律不能写</b>（库中为空时也不能），提交非空值即抛
     *       oauth2RedirectUriPrimaryFrozen——主地址属应用属主，只由网页端配置；AI 的回调地址走补充列表。</li>
     *   <li>{@code source == Manual}（网页/前端人工）：不受限，应用属主对自己的应用全权，
     *       可以删任何来源的条目、可以改主地址。</li>
     * </ul>
     *
     * @param addRedirectUris    要追加的补充回调地址；逐条校验格式，与主地址或已有条目重复的自动丢弃（幂等）。
     *                           null 表示不追加
     * @param removeRedirectUris 要删除的补充回调地址（按 uri 全等匹配）；null 表示不删除。
     *                           删除不存在的地址是 no-op，不计入 skipped
     * @param operatorId         操作人 userId，落进新增条目的 createdBy（审计用，不参与权限判定）
     * @param source             本次写入的来源，由 web 层按 {@code SandboxUtils.isSandboxRequest} 判定后传入；
     *                           null 按 {@link RedirectUriEntry.Source#Manual} 处理
     */
    ThirdAppOAuthSettingResult saveSetting(UserProjectModel project, String homepageUrl, String redirectUri,
            List<String> addRedirectUris, List<String> removeRedirectUris, List<String> scopes,
            Long operatorId, RedirectUriEntry.Source source);

    /** 重新生成凭证：明文密钥仅返回一次，旧凭证立即失效 */
    ThirdAppOAuthCredential regenerateCredential(Long projectId);
}
