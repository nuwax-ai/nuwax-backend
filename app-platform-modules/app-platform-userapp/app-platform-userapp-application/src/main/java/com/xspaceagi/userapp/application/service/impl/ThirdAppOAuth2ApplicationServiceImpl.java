package com.xspaceagi.userapp.application.service.impl;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.ProjectTypeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.userapp.application.oauth2.OAuth2CredentialGenerator;
import com.xspaceagi.userapp.application.oauth2.OAuth2ScopeRegistry;
import com.xspaceagi.userapp.application.oauth2.ThirdAppOAuthCredential;
import com.xspaceagi.userapp.application.oauth2.ThirdAppOAuthSettingResult;
import com.xspaceagi.userapp.application.service.IOAuth2ScopeApplyApplicationService;
import com.xspaceagi.userapp.application.service.IThirdAppOAuth2ApplicationService;
import com.xspaceagi.userapp.application.util.UserAppCredentialCipher;
import com.xspaceagi.userapp.domain.model.UserProjectModel;
import com.xspaceagi.userapp.domain.service.IUserProjectDomainService;
import com.xspaceagi.userapp.sdk.dto.RedirectUriEntry;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ThirdAppOAuth2ApplicationServiceImpl implements IThirdAppOAuth2ApplicationService {

    /** 补充回调地址条数上限 */
    private static final int MAX_SUPPLEMENTARY_URIS = 20;

    /** 单条回调地址长度上限，与主地址列 redirect_uri varchar(512) 对齐 */
    private static final int MAX_REDIRECT_URI_LENGTH = 512;

    @Resource
    private IUserProjectDomainService userProjectDomainService;

    @Resource
    private IOAuth2ScopeApplyApplicationService oauth2ScopeApplyApplicationService;

    @Resource
    private UserAppCredentialCipher userAppCredentialCipher;

    @Override
    public UserProjectModel requireThirdApp(Long projectId) {
        UserProjectModel project = projectId == null ? null : userProjectDomainService.getById(projectId);
        if (project == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.oauth2ClientNotFound);
        }
        if (project.getProjectType() != ProjectTypeEnum.ThirdApp && project.getProjectType() != ProjectTypeEnum.UserApp) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.oauth2ThirdAppOnly);
        }
        return project;
    }

    @Override
    public UserProjectModel findEnabledClient(String clientId) {
        UserProjectModel project = StringUtils.isBlank(clientId) ? null
                : userProjectDomainService.getByClientId(clientId);
        // 停用 / 删除 / 类型不符一律按「客户端不存在」处理，不向调用方泄露接入应用状态
        if (project == null || (project.getProjectType() != ProjectTypeEnum.ThirdApp && project.getProjectType() != ProjectTypeEnum.UserApp)
                || !Boolean.TRUE.equals(project.getOauthEnabled())) {
            return null;
        }
        return project;
    }

    @Override
    public UserProjectModel requireEnabledClient(String clientId) {
        UserProjectModel project = findEnabledClient(clientId);
        if (project == null) {
            throw BizException.of(ErrorCodeEnum.UNAUTHORIZED, BizExceptionCodeEnum.oauth2ClientNotFound);
        }
        return project;
    }

    @Override
    public void verifyClientSecret(UserProjectModel project, String rawSecret) {
        String expected = userAppCredentialCipher.decrypt(project.getOauthClientSecret());
        // 常量时间比较，避免逐字符比较带来的时序侧信道
        boolean matched = expected != null && rawSecret != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), rawSecret.getBytes(StandardCharsets.UTF_8));
        if (!matched) {
            throw BizException.of(ErrorCodeEnum.UNAUTHORIZED, BizExceptionCodeEnum.oauth2ClientSecretInvalid);
        }
    }

    @Override
    public String revealClientSecret(UserProjectModel project) {
        return userAppCredentialCipher.decrypt(project.getOauthClientSecret());
    }

    @Override
    public List<String> resolveScopes(UserProjectModel project) {
        return OAuth2ScopeRegistry.parse(project.getOauthScope());
    }

    @Override
    public List<String> resolveRequestedScopes(UserProjectModel project, String requestedScope) {
        List<String> allowed = resolveScopes(project);
        if (StringUtils.isBlank(requestedScope)) {
            // 未指定 scope：授予接入应用登记（或默认）的全部范围
            return allowed;
        }
        List<String> requested = OAuth2ScopeRegistry.parseRequested(requestedScope);
        if (requested.isEmpty() || !allowed.containsAll(requested)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.oauth2InvalidScope);
        }
        return requested;
    }

    @Override
    public void verifyRedirectUri(UserProjectModel project, String redirectUri) {
        // ★判据是「生效集合为空」，不是「主地址为空」。沙箱里的 AI 只往补充列表 redirect_uris 写，
        //   于是「主地址空 + 补充列表有值」是合法状态；若仍按主地址判，AI 自己登记的回调地址会被判成
        //   「应用没配回调」，授权码流程直接走不通。存量行两者皆空 ⇒ 与升级前行为逐字一致。
        if (!hasEffectiveRedirectUri(project)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.oauth2RedirectUriRequired);
        }
        // 授权码模式要求回调地址精确匹配，避免开放重定向
        if (!matchesRegisteredRedirectUri(project, redirectUri)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.oauth2RedirectUriMismatch);
        }
    }

    /** 生效的回调地址集合（主地址 ∪ 补充条目 uri）是否非空 */
    private static boolean hasEffectiveRedirectUri(UserProjectModel project) {
        if (StringUtils.isNotBlank(project.getRedirectUri())) {
            return true;
        }
        List<RedirectUriEntry> supplementary = project.getRedirectUris();
        if (supplementary == null) {
            return false;
        }
        for (RedirectUriEntry entry : supplementary) {
            if (entry != null && StringUtils.isNotBlank(entry.getUri())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否命中登记的回调地址：主地址 redirect_uri 与补充列表 redirect_uris 取并集，任一全等即通过。
     * <p>
     * 不做通配、前缀与 URL 规范化（不去尾斜杠、不补默认端口、不忽略大小写）：精确匹配是防开放重定向的
     * 唯一硬边界，登记与校验必须用同一把尺子。补充地址在写入时已由 {@link #requireValidUrl(String)} trim，
     * 故这里对入参同样不做 trim，避免放宽匹配面。
     */
    private static boolean matchesRegisteredRedirectUri(UserProjectModel project, String redirectUri) {
        // redirect_uri 是可选请求参数，客户端不传时这里收到 null；且 List.of/copyOf 一类不可变列表的
        // contains(null) 会抛 NPE——显式挡住，使「没传」与升级前一样按不匹配处理，而不是 500
        if (redirectUri == null) {
            return false;
        }
        // 主地址可以为空（AI 只写补充列表的应用就是这个状态），故以入参为接收方调 equals
        if (redirectUri.equals(project.getRedirectUri())) {
            return true;
        }
        List<RedirectUriEntry> supplementary = project.getRedirectUris();
        if (supplementary == null) {
            return false;
        }
        for (RedirectUriEntry entry : supplementary) {
            if (entry != null && redirectUri.equals(entry.getUri())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public UserProjectModel ensureCredential(Long projectId) {
        UserProjectModel project = requireThirdApp(projectId);
        if (StringUtils.isNotBlank(project.getOauthClientId())) {
            return project;
        }
        // 原型语义：平台已为该项目自动生成凭证，无需用户手动触发
        generateCredential(project);
        return requireThirdApp(projectId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ThirdAppOAuthSettingResult saveSetting(UserProjectModel project, String homepageUrl, String redirectUri,
            List<String> addRedirectUris, List<String> removeRedirectUris, List<String> scopes,
            Long operatorId, RedirectUriEntry.Source source) {
        // null 来源按人工处理：人工是全权方，把它当 Sandbox 会凭空收窄权限
        RedirectUriEntry.Source effectiveSource = source != null ? source : RedirectUriEntry.Source.Manual;
        UserProjectModel update = new UserProjectModel();
        update.setId(project.getId());
        // 留空表示不修改该项（仓储按非空字段更新，不会把已有值抹掉）
        if (StringUtils.isNotBlank(homepageUrl)) {
            update.setHomepageUrl(requireValidUrl(homepageUrl));
        }
        if (StringUtils.isNotBlank(redirectUri)) {
            // 主地址只属于应用属主：沙箱/AI 一律不许写，库中为空时也不许（没有「放行一次」的例外）。
            // 理由：主地址是网页端以及存量集成认的那一个，让 AI 占位等于把后续人工配置挡在 3353 之外，
            // 而 AI 自己的回调地址本来就该走补充列表——那里按来源隔离、互不踩踏，且生效集合取并集，
            // 主地址为空完全不影响授权。要配主地址请到网页端。
            if (effectiveSource == RedirectUriEntry.Source.Sandbox) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM,
                        BizExceptionCodeEnum.oauth2RedirectUriPrimaryFrozen);
            }
            update.setRedirectUri(requireValidUrl(redirectUri));
        }
        // 补充回调地址：显式增删，两个参数都为 null 时完全不碰这一列
        List<String> skippedRemovals = List.of();
        if (addRedirectUris != null || removeRedirectUris != null) {
            SupplementaryUriChange change = applySupplementaryUriChange(project, update.getRedirectUri(),
                    addRedirectUris, removeRedirectUris, operatorId, effectiveSource);
            update.setRedirectUris(change.entries());
            skippedRemovals = change.skippedRemovals();
        }
        if (update.getHomepageUrl() != null || update.getRedirectUri() != null || update.getRedirectUris() != null) {
            userProjectDomainService.update(update);
        }
        logRedirectUriChange(project, update, operatorId, effectiveSource, skippedRemovals);
        // scope 范围变更不直接生效：落一条待审核申请，管理员审核通过后才写回 oauth_scope（期间按原值生效）
        if (scopes != null) {
            oauth2ScopeApplyApplicationService.submit(project, operatorId, scopes);
        }
        if (update.getHomepageUrl() != null) {
            project.setHomepageUrl(update.getHomepageUrl());
        }
        if (update.getRedirectUri() != null) {
            project.setRedirectUri(update.getRedirectUri());
        }
        if (update.getRedirectUris() != null) {
            project.setRedirectUris(update.getRedirectUris());
        }
        return new ThirdAppOAuthSettingResult(project, skippedRemovals);
    }

    /**
     * 一次增删的结果。
     *
     * @param entries          落库用的完整条目列表（增删都已完成）
     * @param skippedRemovals  请求删除、但因不属于本次来源而被跳过的地址
     */
    private record SupplementaryUriChange(List<RedirectUriEntry> entries, List<String> skippedRemovals) {
    }

    /**
     * 在库中现有条目上做增删，返回落库用的完整列表。
     * <p>
     * 增删都在同一份 list 上顺序完成、由调用方一次落库（方法已在事务里），避免两次 update 互相覆盖。
     * 新增条目逐条走 {@link #requireValidUrl(String)}：trim → 校验绝对 http(s) → 丢空串 →
     * 与主地址重复的丢弃（由主地址承载，避免同一地址两个来源）→ 已存在的丢弃（幂等，且<b>不改它的来源</b>：
     * 先来者的归属不变，否则 AI 重跑一次就能把人工条目「认领」成自己的、进而获得删除权）。
     * 不做任何 URL 规范化，与 {@link #matchesRegisteredRedirectUri} 的全等匹配保持同一把尺子。
     *
     * @param newPrimaryUri 本次同批提交的新主地址（null 表示主地址未变，取库中现值）
     */
    private static SupplementaryUriChange applySupplementaryUriChange(UserProjectModel project, String newPrimaryUri,
            List<String> addRedirectUris, List<String> removeRedirectUris, Long operatorId,
            RedirectUriEntry.Source source) {
        String primary = newPrimaryUri != null ? newPrimaryUri : project.getRedirectUri();
        // 拷贝一份再改：project 上的原列表在失败路径还要用于日志对照
        List<RedirectUriEntry> entries = new ArrayList<>();
        if (project.getRedirectUris() != null) {
            for (RedirectUriEntry existing : project.getRedirectUris()) {
                if (existing != null && StringUtils.isNotBlank(existing.getUri())) {
                    entries.add(existing);
                }
            }
        }

        // ---- ① 删除：Sandbox 只能删 Sandbox 条目
        List<String> skippedRemovals = new ArrayList<>();
        if (removeRedirectUris != null) {
            for (String raw : removeRedirectUris) {
                if (StringUtils.isBlank(raw)) {
                    continue;
                }
                String target = raw.trim();
                boolean blocked = false;
                Iterator<RedirectUriEntry> iterator = entries.iterator();
                while (iterator.hasNext()) {
                    RedirectUriEntry entry = iterator.next();
                    if (!target.equals(entry.getUri())) {
                        continue;
                    }
                    // source 为 null 的历史条目按人工处理（fail-closed：宁可让 AI 删不动，
                    // 也不能把归属不明的条目当成 AI 自己的放出去）
                    if (source == RedirectUriEntry.Source.Sandbox
                            && entry.getSource() != RedirectUriEntry.Source.Sandbox) {
                        blocked = true;
                        continue;
                    }
                    iterator.remove();
                }
                if (blocked) {
                    skippedRemovals.add(target);
                }
                // 删一个不存在的地址是 no-op，不计入 skipped：它已经不在列表里，没有需要回报的事
            }
        }

        // ---- ② 新增：打上本次来源与操作人
        if (addRedirectUris != null) {
            Date now = new Date();
            for (String raw : addRedirectUris) {
                if (StringUtils.isBlank(raw)) {
                    continue;
                }
                String valid = requireValidUrl(raw);
                if (valid.length() > MAX_REDIRECT_URI_LENGTH) {
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM,
                            BizExceptionCodeEnum.oauth2RedirectUriExceeded);
                }
                if (valid.equals(primary) || containsUri(entries, valid)) {
                    continue;
                }
                entries.add(RedirectUriEntry.builder()
                        .uri(valid)
                        .source(source)
                        .createdBy(operatorId)
                        .createdAt(now)
                        .build());
            }
        }
        if (entries.size() > MAX_SUPPLEMENTARY_URIS) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.oauth2RedirectUriExceeded);
        }
        return new SupplementaryUriChange(entries, skippedRemovals);
    }

    private static boolean containsUri(List<RedirectUriEntry> entries, String uri) {
        for (RedirectUriEntry entry : entries) {
            if (uri.equals(entry.getUri())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 回调地址是开放重定向的安全边界，变更留审计痕迹（不含密钥）。
     * 一并记下来源与被跳过的删除：出问题时能直接看出「谁改的」与「谁想改但没改成」。
     */
    private void logRedirectUriChange(UserProjectModel project, UserProjectModel update, Long operatorId,
            RedirectUriEntry.Source source, List<String> skippedRemovals) {
        boolean primaryChanged = update.getRedirectUri() != null;
        boolean supplementaryChanged = update.getRedirectUris() != null;
        if (!primaryChanged && !supplementaryChanged) {
            return;
        }
        log.info("Third app oauth2 redirect uri changed, projectId={}, operatorId={}, source={}, primary: {} -> {},"
                        + " supplementary: {} -> {}, skippedRemovals={}", project.getId(), operatorId, source,
                project.getRedirectUri(), primaryChanged ? update.getRedirectUri() : "(unchanged)",
                urisOf(project.getRedirectUris()), supplementaryChanged ? urisOf(update.getRedirectUris())
                        : "(unchanged)", skippedRemovals);
    }

    /** 日志只打 uri，来源与时间戳在列里已有，避免一行日志过长 */
    private static List<String> urisOf(List<RedirectUriEntry> entries) {
        if (entries == null) {
            return null;
        }
        List<String> uris = new ArrayList<>(entries.size());
        for (RedirectUriEntry entry : entries) {
            if (entry != null) {
                uris.add(entry.getUri());
            }
        }
        return uris;
    }

    @Override
    public ThirdAppOAuthCredential regenerateCredential(Long projectId) {
        UserProjectModel project = requireThirdApp(projectId);
        ThirdAppOAuthCredential credential = generateCredential(project);
        log.info("Third app oauth2 credential regenerated, projectId={}, clientId={}", projectId,
                credential.clientId());
        return credential;
    }

    /**
     * 生成并落库新凭证。
     * <p>
     * 旧凭证无需显式清理：client_id 已变更，旧值在库中不再存在，
     * 令牌校验按 client_id 反查接入应用时必然失败，已签发令牌随之立即失效。
     */
    private ThirdAppOAuthCredential generateCredential(UserProjectModel project) {
        String clientId = OAuth2CredentialGenerator.newClientId();
        String clientSecret = OAuth2CredentialGenerator.newClientSecret();
        UserProjectModel update = new UserProjectModel();
        update.setId(project.getId());
        update.setOauthClientId(clientId);
        update.setOauthClientSecret(userAppCredentialCipher.encrypt(clientSecret));
        if (project.getOauthEnabled() == null) {
            update.setOauthEnabled(Boolean.TRUE);
        }
        userProjectDomainService.update(update);
        return new ThirdAppOAuthCredential(clientId, clientSecret);
    }

    /**
     * 主页地址 / 回调地址必须是绝对 http(s) 地址且不带 fragment
     */
    private static String requireValidUrl(String url) {
        String trimmed = url.trim();
        try {
            URI uri = new URI(trimmed);
            String scheme = uri.getScheme();
            boolean httpScheme = scheme != null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
            if (!httpScheme || StringUtils.isBlank(uri.getHost()) || uri.getFragment() != null) {
                throw new URISyntaxException(trimmed, "unsupported url");
            }
            return trimmed;
        } catch (URISyntaxException e) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.oauth2UrlInvalid);
        }
    }
}
