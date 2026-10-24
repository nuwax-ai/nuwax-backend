package com.xspaceagi.repo.application.service;

import com.xspaceagi.repo.application.service.dto.RepoAccessResult;
import com.xspaceagi.repo.application.service.dto.RepoPublicShareView;
import com.xspaceagi.repo.domain.model.RepoAttachmentDomain;
import com.xspaceagi.repo.domain.model.RepoPageDomain;
import com.xspaceagi.repo.domain.model.RepoShareLinkDomain;
import com.xspaceagi.repo.domain.repository.RepoAttachmentRepository;
import com.xspaceagi.repo.domain.repository.RepoPageRepository;
import com.xspaceagi.repo.domain.repository.RepoShareLinkRepository;
import com.xspaceagi.repo.spec.constants.RepoConstants;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 匿名分享链接访问服务（需求4决策②：必须支持未登录查看）。
 * <p>
 * 分享链接跨租户匿名访问，查询经 {@link TenantFunctions#callWithIgnoreCheck} 绕过租户过滤，
 * 命中后按页面所属租户覆盖 RequestContext（豁免路径默认 tenantId=1），
 * 与 RepoInternalAuthController 第 3 步同一模式。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepoPublicShareService {

    private final RepoShareLinkRepository repoShareLinkRepository;

    private final RepoPageRepository repoPageRepository;

    private final RepoAttachmentRepository repoAttachmentRepository;

    private final RepoAccessService repoAccessService;

    private final RepoFileUrlService repoFileUrlService;

    private final UserApplicationService userApplicationService;

    /**
     * 按 token 取公开分享内容（匿名可调）。
     * <ul>
     *   <li>token 无效/页面不存在 → resourceDataNotFound；</li>
     *   <li>链接为 login 类型且访问者未登录 → userNotLoggedIn，前端引导登录；已登录放行。</li>
     * </ul>
     * 输出前给内容里的 /api/f/{key} 文件地址补 ak（需求6）：匿名端无 cookie，
     * 带 ak/presigned 的绝对地址才能直访；存储层快照不受影响。
     *
     * @param baseUrl 请求来源绝对基址（如 http://host:port），可空则不补 ak
     */
    public RepoPublicShareView getPublicShare(String token, String baseUrl) {
        ShareResolution resolution = resolveSharedPage(token);
        RepoPageDomain page = resolution.page();
        RepoShareLinkDomain link = resolution.link();
        Long userId = RequestContext.get() == null ? null : RequestContext.get().getUserId();
        // 裁决评论权限（需求5）：匿名恒为 false；登录按链接 allow_comment 与空间/分享权限位
        RepoAccessResult access = repoAccessService.resolveAccess(page, userId, token);
        return RepoPublicShareView.builder()
                .pageId(page.getId())
                .slugId(page.getSlugId())
                .title(page.getTitle())
                .spaceId(page.getSpaceId())
                .contentJson(repoFileUrlService.enrichFileUrlAk(page.getContentSnapshot(), baseUrl))
                .html(repoFileUrlService.enrichFileUrlAk(page.getHtmlSnapshot(), baseUrl))
                .sourceExt(page.getSourceExt())
                .pageType(page.getPageType())
                .creatorId(page.getCreatorId())
                .creatorName(resolveDisplayName(page.getCreatorId()))
                .creatorAvatar(resolveAvatar(page.getCreatorId()))
                .ownerId(link.getCreatedBy())
                .ownerName(resolveDisplayName(link.getCreatedBy()))
                .ownerAvatar(resolveAvatar(link.getCreatedBy()))
                .editedAt(page.getEditedAt())
                .commentable(access.isCommentable())
                .allowComment(link.getAllowComment())
                .exportable(access.isExportable())
                .copyable(access.isCopyable())
                .build();
    }

    /**
     * 校验 fileKey 属于该分享页面（正文图片引用或附件列表），防止有效 token 越权读任意文件。
     */
    public void checkFileAccessible(String token, String fileKey) {
        RepoPageDomain page = resolveSharedPage(token).page();
        String html = page.getHtmlSnapshot() == null ? "" : page.getHtmlSnapshot();
        if (html.contains("/api/f/" + fileKey) || html.contains("\"" + fileKey + "\"")) {
            return;
        }
        List<RepoAttachmentDomain> attachments = repoAttachmentRepository.listByPageId(page.getId());
        for (RepoAttachmentDomain attachment : attachments) {
            if (fileKey.equals(attachment.getFileKey())) {
                return;
            }
        }
        throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound);
    }

    /**
     * token → 链接 + 页面（含租户上下文覆盖）：public 链接任何人可访问；
     * login 链接仅未登录访问时返回登录提示错误，已登录用户放行。
     */
    private ShareResolution resolveSharedPage(String token) {
        if (token == null || token.isBlank()) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound);
        }
        // 跨租户查链接与页面（匿名请求无租户上下文）
        RepoShareLinkDomain link = TenantFunctions.callWithIgnoreCheck(
                () -> repoShareLinkRepository.findByToken(token));
        if (link == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound);
        }
        RepoPageDomain page = TenantFunctions.callWithIgnoreCheck(
                () -> repoPageRepository.findById(link.getPageId()));
        if (page == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.resourceDataNotFound);
        }
        // login 链接：仅未登录（匿名）访问时提示登录；豁免路径上拦截器仍会解析 ticket，
        // 已登录用户（RequestContext 有 userId）正常放行
        if (!RepoConstants.SHARE_LINK_PUBLIC.equals(link.getAccessType())
                && (RequestContext.get() == null || RequestContext.get().getUserId() == null)) {
            throw BizException.of(ErrorCodeEnum.UNAUTHORIZED, BizExceptionCodeEnum.userNotLoggedIn);
        }
        // 覆盖租户上下文（豁免路径默认 tenantId=1，后续附件查询/文件下载需按页面租户）
        RequestContext.get().setTenantId(page.getTenantId());
        return new ShareResolution(link, page);
    }

    /**
     * 分享解析结果：链接 + 页面。
     */
    private record ShareResolution(RepoShareLinkDomain link, RepoPageDomain page) {
    }

    /** 按 userId 查展示名（昵称优先，昵称空用用户名）；用户不存在或名字为空返回 null */
    private String resolveDisplayName(Long userId) {
        if (userId == null) return null;
        UserDto user = userApplicationService.queryById(userId);
        if (user == null) return null;
        String nickName = user.getNickName();
        String userName = user.getUserName();
        if (nickName != null && !nickName.isBlank()) return nickName;
        if (userName != null && !userName.isBlank()) return userName;
        return null;
    }

    /** 按 userId 查头像原值；用户不存在返回 null */
    private String resolveAvatar(Long userId) {
        if (userId == null) return null;
        UserDto user = userApplicationService.queryById(userId);
        return user == null ? null : user.getAvatar();
    }
}
