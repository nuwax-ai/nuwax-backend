package com.xspaceagi.repo.web.controller.internal;

import com.xspaceagi.repo.application.service.RepoAccessService;
import com.xspaceagi.repo.application.service.RepoPageApplicationService;
import com.xspaceagi.repo.application.service.dto.RepoAccessResult;
import com.xspaceagi.repo.domain.model.RepoPageDomain;
import com.xspaceagi.repo.web.dto.RepoCollabAuthRequest;
import com.xspaceagi.repo.web.dto.RepoCollabAuthResult;
import com.xspaceagi.repo.web.dto.RepoSpaceAuthRequest;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.AuthService;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.exception.SpacePermissionException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Sidecar 内部鉴权回调。
 * <p>
 * 该路径在 bootstrap auth.excludePath 中豁免登录（由共享密钥保护），流程：
 * <ol>
 *   <li>校验请求头 X-Collab-Secret 与配置 repo.collab.internal-secret 一致（常量时间比较）；</li>
 *   <li>用 AuthService 校验前端 JWT，得到用户；</li>
 *   <li>将 RequestContext 覆盖为该用户的租户/身份（豁免路径默认 tenantId=1，必须覆盖，否则后续租户隔离查询错位）；</li>
 *   <li>按 slugId 查页面（应用服务内含空间成员校验）并返回空间角色。</li>
 * </ol>
 * 失败语义：密钥错误/token 无效 401，页面不存在 404，非空间成员 403。
 */
@Slf4j
@RestController
@RequestMapping("/api/internal/collab")
@RequiredArgsConstructor
public class RepoInternalAuthController {

    private static final String SECRET_HEADER = "X-Collab-Secret";

    private final AuthService authService;

    private final RepoPageApplicationService repoPageApplicationService;

    private final RepoAccessService repoAccessService;

    private final SpacePermissionService spacePermissionService;

    @Value("${repo.collab.internal-secret:}")
    private String internalSecret;

    @PostMapping("/auth")
    public ResponseEntity<RepoCollabAuthResult> auth(HttpServletRequest request,
                                                     @RequestBody(required = false) RepoCollabAuthRequest body) {
        // 1. 共享密钥（未配置视为未启用，直接拒绝，fail-closed）
        String secret = request.getHeader(SECRET_HEADER);
        if (StringUtils.isBlank(internalSecret) || secret == null
                || !MessageDigest.isEqual(internalSecret.getBytes(StandardCharsets.UTF_8),
                secret.getBytes(StandardCharsets.UTF_8))) {
            log.warn("collab auth rejected: bad or missing {}", SECRET_HEADER);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // 2. 参数与 JWT 校验
        if (body == null || StringUtils.isBlank(body.getToken()) || StringUtils.isBlank(body.getSlugId())) {
            return ResponseEntity.badRequest().build();
        }
        UserDto userDto;
        try {
            userDto = authService.getLoginUserInfo(body.getToken());
        } catch (Exception e) {
            log.warn("collab auth rejected: token verify failed, slugId={}, error={}", body.getSlugId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (userDto == null || userDto.getId() == null || userDto.getTenantId() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // 3. 覆盖请求上下文（豁免路径默认 tenantId=1）
        RequestContext<UserDto> context = RequestContext.get();
        context.setTenantId(userDto.getTenantId());
        context.setUserId(userDto.getId());
        context.setUser(userDto);
        context.setLogin(true);

        // 4. 页面存在性 + 访问校验：带 shareToken 时按分享链接裁决（纯链接授权用户
        //    非源空间成员也能通过），否则按源空间成员校验（非成员抛 SpacePermissionException）
        RepoPageDomain page;
        try {
            page = repoPageApplicationService.getPageBySlug(body.getSlugId(), body.getShareToken());
        } catch (SpacePermissionException e) {
            log.info("collab auth rejected: user {} not member of space, slugId={}", userDto.getId(), body.getSlugId());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (page == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        // 成员校验已通过，角色必然存在（平台管理员兜底为 Admin）；纯链接授权用户非成员时角色为 null，回落 User
        String role = spacePermissionService.getSpaceUserRole(page.getSpaceId(), userDto.getId());
        // 统一裁决可编辑性：带 shareToken 时合并链接权限位；被分享只读/文档级权限降级用户，Sidecar 置连接只读
        RepoAccessResult access = repoAccessService.resolveAccess(page, body.getShareToken());
        return ResponseEntity.ok(RepoCollabAuthResult.builder()
                .userId(userDto.getId())
                .tenantId(userDto.getTenantId())
                .spaceId(page.getSpaceId())
                .role(role == null ? "User" : role)
                .editable(access != null && access.isEditable())
                .build());
    }

    /**
     * 空间信号文档鉴权（信号 doc 方案）。
     * <p>
     * 前端任意页面（doc 编辑器/门户/空间/分享）额外连一条 `tree_&lt;spaceId&gt;` Hocuspocus 连接，
     * 只接收该空间目录树的服务器端变更信号（meta.treeVersion）。校验逻辑：
     * <ol>
     *   <li>共享密钥（同 /auth）;</li>
     *   <li>校验 JWT 得到用户，覆盖 RequestContext;</li>
     *   <li>按 spaceId 查空间成员角色，非成员 403（Sidecar 收非 2xx 即放宽为「仅连接」兜底，见 auth.ts）。</li>
     * </ol>
     * 返回：{@link RepoCollabAuthResult}（userId/tenantId/spaceId/role/editable）。信号文档不含文档内容，
     * 仅服务端写 meta.treeVersion，浏览器端只观察不改写，故 editable 恒 true。
     */
    @PostMapping("/space-auth")
    public ResponseEntity<RepoCollabAuthResult> spaceAuth(HttpServletRequest request,
                                                          @RequestBody(required = false) RepoSpaceAuthRequest body) {
        // 1. 共享密钥（未配置视为未启用，直接拒绝，fail-closed）
        String secret = request.getHeader(SECRET_HEADER);
        if (StringUtils.isBlank(internalSecret) || secret == null
                || !MessageDigest.isEqual(internalSecret.getBytes(StandardCharsets.UTF_8),
                secret.getBytes(StandardCharsets.UTF_8))) {
            log.warn("collab space-auth rejected: bad or missing {}", SECRET_HEADER);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // 2. 参数与 JWT 校验
        if (body == null || StringUtils.isBlank(body.getToken()) || body.getSpaceId() == null) {
            return ResponseEntity.badRequest().build();
        }
        UserDto userDto;
        try {
            userDto = authService.getLoginUserInfo(body.getToken());
        } catch (Exception e) {
            log.warn("collab space-auth rejected: token verify failed, spaceId={}, error={}", body.getSpaceId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (userDto == null || userDto.getId() == null || userDto.getTenantId() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // 3. 覆盖请求上下文（豁免路径默认 tenantId=1，须覆盖，否则空间成员查询租户错位）
        RequestContext<UserDto> context = RequestContext.get();
        context.setTenantId(userDto.getTenantId());
        context.setUserId(userDto.getId());
        context.setUser(userDto);
        context.setLogin(true);

        // 4. 空间成员校验：非成员返回 403（Sidecar 据此放宽为「仅连接」兜底）
        String role = spacePermissionService.getSpaceUserRole(body.getSpaceId(), userDto.getId());
        if (role == null) {
            log.info("collab space-auth rejected: user {} not member of space {}", userDto.getId(), body.getSpaceId());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        return ResponseEntity.ok(RepoCollabAuthResult.builder()
                .userId(userDto.getId())
                .tenantId(userDto.getTenantId())
                .spaceId(body.getSpaceId())
                .role(role)
                .editable(true)
                .build());
    }
}
