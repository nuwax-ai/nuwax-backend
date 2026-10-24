package com.xspaceagi.ctl.oauth2;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.xspaceagi.agent.core.adapter.application.PublishApplicationService;
import com.xspaceagi.agent.core.adapter.dto.PublishedPermissionDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.file.application.service.FileManagementService;
import com.xspaceagi.file.domain.model.FileRecordDomain;
import com.xspaceagi.file.sdk.IFileAccessService;
import com.xspaceagi.file.web.dto.FileRecordVO;
import com.xspaceagi.system.application.dto.SpaceDto;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

import static com.xspaceagi.file.web.controller.FileManagementController.checkSvgUploadPermission;
import static com.xspaceagi.file.web.controller.FileManagementController.toVO;

/**
 * OAuth2 门面 —— 用户信息组（空间查询、文件上传与认证）。
 * <p>
 * ★<b>为什么是门面而不是直接开放 {@code /api/v1/**}</b>：{@code /api/v1/} 前缀由
 * {@code ApiKeyInterceptor}（order 23）独占鉴权，它只认 {@code ak-}/{@code ck-} 前缀，
 * 对其它一律抛 {@code apiKeyMissing}；而 {@code /api/v1/**} 又在 {@code auth.excludePath} 里，
 * {@code AuthInterceptor} 不会校验 {@code oa-} 令牌。要让 OAuth2 令牌直接打 {@code /api/v1/**}
 * 就得改这两个共享件，风险外溢到所有既有开放接口。
 * 门面路径 {@code /api/oauth2/**} 则天然两者都不碰：不在 {@code excludePath}（除 token/revoke）
 * ⇒ {@code AuthInterceptor} 正常校验令牌并按 {@code OAuth2ScopeRegistry} 判 scope；
 * 不以 {@code /api/v1/} 开头 ⇒ {@code ApiKeyInterceptor} 直接放行。<b>零拦截器改动</b>。
 * <p>
 * ★鉴权与 scope 判定<b>不在本类</b>：拦截器已完成，本类只做「取当前授权用户 → 转调既有 service」。
 * 因此这里的每个方法都必须只用 {@code RequestContext} 里的 userId/tenantId，
 * <b>绝不接受调用方传入的 userId/tenantId</b>——那是越权的入口。
 * <p>
 * 与协议端点（{@code /token}、{@code /userinfo}）不同，门面是平台自有资源接口，
 * 统一用 {@code ReqResult} 信封，与站内其它 {@code /api/**} 保持一致。
 */
@Tag(name = "OAuth2 门面-用户与文件", description = "需 OAuth2 access_token（oa- 前缀）")
@RestController
@RequestMapping("/api/oauth2")
@Slf4j
public class OAuth2UserFacadeController {

    @Resource
    private SpaceApplicationService spaceApplicationService;

    @Resource
    private FileManagementService fileManagementService;

    @Resource
    private IFileAccessService iFileAccessService;

    @Resource
    private PublishApplicationService publishApplicationService;

    /**
     * 授权用户可见的空间列表（scope：{@code user:space}）。
     * <p>
     * 用 {@code queryVisibleListByUserId}：只返回个人空间与团队空间，不含私聊 / 群组镜像空间——
     * 对外暴露「我有哪些聊天会话对应的空间」没有业务意义，只会放大信息面。
     * 返回项已带 {@code currentUserRole}，接入方可据此判断能否在该空间下建资源。
     * <p>
     * ⚠️ 本方法<b>不可</b>用于鉴权（会漏 DM/Group 空间导致误判无权）；
     * 需要「用户可访问的 spaceId 全集」时要用 {@code queryListByUserId}。
     */
    @Operation(summary = "查询授权用户可见的空间列表")
    @GetMapping("/space/list")
    public ReqResult<List<SpaceDto>> spaceList() {
        Long userId = RequestContext.get().getUserId();
        Long tenantId = RequestContext.get().getTenantId();
        List<SpaceDto> spaces = spaceApplicationService.queryVisibleListByUserId(userId);
        if (spaces == null || spaces.isEmpty()) {
            return ReqResult.success(List.of());
        }
        // 租户二次收口：MyBatis 的租户行级过滤已按 RequestContext.tenantId 生效，
        // 这里再兜一层，避免底层某个查询豁免了租户过滤时把别租户的空间漏出去
        List<SpaceDto> sameTenant = spaces.stream()
                .filter(space -> Objects.equals(space.getTenantId(), tenantId))
                .toList();
        if (sameTenant.size() != spaces.size()) {
            log.warn("OAuth2 space list dropped cross-tenant rows, userId={}, tenantId={}, total={}, kept={}",
                    userId, tenantId, spaces.size(), sameTenant.size());
        }
        return ReqResult.success(sameTenant);
    }

    /**
     * 以授权用户身份上传文件（scope：{@code file:write}）。
     * <p>
     * 口径与开放接口 {@code POST /api/v1/file/upload} <b>完全一致</b>（同一套校验、同一个 service）：
     * <ul>
     * <li>SVG 可携带脚本、同源内联展示有存储型 XSS 风险 ⇒ 仅管理员可传（{@code checkSvgUploadPermission}）；</li>
     * <li>{@code targetType=Agent} 且 {@code targetId>0} 时校验该智能体的可见权限，
     * 否则第三方可以往任意智能体下塞文件；</li>
     * <li>{@code isAuthRequired=true}：落库即标记「需鉴权访问」，返回的 fileUrl <b>不能</b>匿名打开，
     * 必须再调 {@link #fileUrl(String, Integer)} 换带 {@code ak} 的临时地址。
     * 这一对（上传 + 认证）就是需求里的「文件上传与认证」。</li>
     * </ul>
     */
    @Operation(summary = "上传文件（返回的 fileUrl 需再调 /file/url 换带凭证地址）")
    @PostMapping("/file/upload")
    public ReqResult<FileRecordVO> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "targetType", required = false, defaultValue = "Default") String targetType,
            @RequestParam(value = "targetId", required = false, defaultValue = "-1") Long targetId,
            @RequestParam(value = "metadata", required = false) String metadata) {
        checkSvgUploadPermission(file);
        if ("Agent".equals(targetType) && targetId != null && targetId > 0) {
            PublishedPermissionDto permission = publishApplicationService.hasPermission(Published.TargetType.Agent,
                    targetId);
            if (permission == null || !permission.isView()) {
                return ReqResult.error("No permission to upload file for this agent id");
            }
        }
        FileRecordDomain record = fileManagementService.uploadFile(file, RequestContext.get().getTenantId(),
                RequestContext.get().getUserId(), targetType, targetId, metadata, true);
        return ReqResult.success(toVO(record));
    }

    /**
     * 为文件签发带访问凭证（{@code ak}）的下载 / 预览地址（scope：{@code file:read}）。
     * <p>
     * 即需求里的「文件认证」：上传返回的 fileUrl 是受保护的，本端点把它换成可匿名访问的临时地址
     * （{@code ak} 存 Redis，TTL 24h；云存储会再 302 到 1h 有效的预签名地址）。
     * <p>
     * 入参里已有的 {@code ak} 会先剥掉再重签，避免旧凭证被叠加成 {@code ak=a&ak=b}。
     * <p>
     * ⚠️ 与 {@code GET /api/v1/file/ak} 语义一致：本端点<b>不校验</b> fileUrl 是否属于当前授权用户
     * （凭证按路径签发）。这是既有开放接口的口径，门面保持一致以免出现两套行为；
     * 因此 {@code file:read} 应视为「能签发任意已知路径的文件凭证」，登记该 scope 时需知情。
     */
    @Operation(summary = "为文件签发带 ak 的临时访问地址")
    @GetMapping("/file/url")
    public ReqResult<String> fileUrl(@RequestParam("fileUrl") String fileUrl,
                                     @RequestParam(value = "download", required = false) Integer download) {
        String decoded = URLDecoder.decode(fileUrl, StandardCharsets.UTF_8);
        // 移除已有的 ak 参数（含 ak 位于串尾时留下的孤立 '?'）
        decoded = decoded.replaceAll("([?&])ak(?:=[^&]*)?&?", "$1").replaceAll("\\?$", "");
        return ReqResult.success(iFileAccessService.getFileUrlWithAk(decoded, true, download != null ? download : 0));
    }
}
