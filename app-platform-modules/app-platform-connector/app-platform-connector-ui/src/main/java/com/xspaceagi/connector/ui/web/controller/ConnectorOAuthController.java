package com.xspaceagi.connector.ui.web.controller;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.xspaceagi.connector.domain.entity.ConnectorProvider;
import com.xspaceagi.connector.domain.service.ConnectorCatalogService;
import com.xspaceagi.connector.domain.service.ConnectorOAuthService;
import com.xspaceagi.connector.spec.error.ConnectorErrorCode;
import com.xspaceagi.connector.spec.exception.ConnectorException;
import com.xspaceagi.connector.ui.web.controller.dto.DeviceAuthorizeVo;
import com.xspaceagi.connector.ui.web.controller.dto.DevicePollDto;
import com.xspaceagi.connector.ui.web.controller.dto.DevicePollVo;
import com.xspaceagi.connector.ui.web.controller.dto.OauthConfigSaveDto;
import com.xspaceagi.connector.ui.web.controller.dto.OauthExchangeDto;
import com.xspaceagi.connector.ui.web.controller.dto.OauthExchangeVo;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * OAuth 授权流程入口（用户侧），含两种机制：授权码（oauth2）与设备码扫码（oauth2_device）。
 * <p>
 * callback 返回自包含 HTML 完成页（无外部资源，防供应链与 XSS：全部输出转义）。
 * <p>
 * 提交回调的两个入口（{@code /callback} 与 {@code /exchange}）<b>免登录</b>：两者共用
 * {@code handleCallback}，身份都由 state 承载（见 {@code ConnectorOAuthServiceImpl#handleCallback}），
 * 白名单里各占一条叶子路径。除此以外本控制器的接口都要求登录态。
 * <p>
 * 扫码三端点（{@code /device/authorize}、{@code /device/qr}、{@code /device/poll}）
 * <b>全部要求登录态</b>：这里刻意不加进白名单。扫码流程没有「IdP 重定向回来的匿名请求」
 * 这一环——发起、取图、轮询都由已登录的前端驱动，身份由登录态直接给出，
 * 故可以要求得比回调更严。
 */
@Tag(name = "连接器 OAuth 接口")
@RestController
@RequestMapping("/api/connector/oauth")
public class ConnectorOAuthController {

    /**
     * 二维码边长（像素）。与 platform-system 的 IconGenerateController 取值一致：
     * 待编码文本约 122 字符（QR version 6-7，约 45 个模块），300px 下每个模块 6-7 像素，
     * 手机摄像头在正常距离上就能扫到。
     */
    private static final int QR_SIZE = 300;

    @Resource
    private ConnectorOAuthService oauthService;

    @Resource
    private ConnectorCatalogService catalogService;

    @Resource
    private SpacePermissionService spacePermissionService;

    @GetMapping("/authorize")
    @Operation(summary = "发起 OAuth 授权：返回授权 URL（state+PKCE，15 分钟有效）")
    public ReqResult<Map<String, String>> authorize(@RequestParam String service,
                                                    @RequestParam(required = false) Long spaceId) {
        ConnectorProvider provider = catalogService.requireProvider(service);
        // 官方连接器全局可见、无空间归属：不校验 spaceId；null 仅作“跳过权限检查”哨兵，
        // state 表 space_id 有默认值 0，授权连接最终落 spaceId=0（对齐官方语义）
        Long effectiveSpaceId = provider.isOfficial() ? null : spaceId;
        if (effectiveSpaceId != null) {
            spacePermissionService.checkSpaceUserPermission(effectiveSpaceId);
        }
        String authorizeUrl = oauthService.buildAuthorizeUrl(service, effectiveSpaceId);
        Map<String, String> result = new HashMap<>();
        result.put("authorizeUrl", authorizeUrl);
        return ReqResult.success(result);
    }

    @GetMapping(value = "/callback", produces = MediaType.TEXT_HTML_VALUE)
    @Operation(summary = "OAuth 回调（IdP 重定向入口）：换 token、建连接、渲染完成页")
    public String callback(@RequestParam(required = false) String state,
                           @RequestParam(required = false) String code,
                           @RequestParam(required = false) String error) {
        ConnectorOAuthService.CallbackOutcome outcome = oauthService.handleCallback(state, code, error);
        return renderCompletionPage(outcome);
    }

    /**
     * JSON 版回调：与 /callback 共用同一套处理逻辑，只是把自包含 HTML 完成页换成结构化结果。
     * <p>
     * 存在的理由：浏览器回调必须回 HTML（给人看），程序判成败只能读页面文案，分不清
     * 「state 已过期」和「token 交换失败」。授权链接若是在别的浏览器里点开的，
     * 把地址栏里的 code+state 提交到这里即可建连，不依赖调用方的登录态
     * （身份由 state 承载，见 ConnectorOAuthServiceImpl#handleCallback）。
     */
    @PostMapping("/exchange")
    @Operation(summary = "提交授权回调（code+state）并返回结构化结果：不渲染 HTML，供程序判定成败")
    public ReqResult<OauthExchangeVo> exchange(@Valid @RequestBody OauthExchangeDto dto) {
        ConnectorOAuthService.CallbackOutcome outcome =
                oauthService.handleCallback(dto.getState(), dto.getCode(), dto.getError());
        return ReqResult.success(new OauthExchangeVo(outcome.success(), outcome.providerService(),
                outcome.displayName(), outcome.message(), outcome.connectionId()));
    }

    @PostMapping("/byo-config")
    @Operation(summary = "保存本人 BYO OAuth App 配置")
    public ReqResult<Void> saveByoConfig(@Valid @RequestBody OauthConfigSaveDto dto) {
        oauthService.saveConfig(dto.getService(), "byo", RequestContext.get().getUserId(),
                dto.getClientId(), dto.getClientSecret(), dto.getAuthUrl(), dto.getTokenUrl(), dto.getScopes());
        return ReqResult.success();
    }

    @GetMapping("/byo-config")
    @Operation(summary = "查看本人 BYO OAuth App 配置（client_secret 脱敏）")
    public ReqResult<ConnectorOAuthService.OauthConfigView> getByoConfig(@RequestParam String service) {
        ConnectorOAuthService.OauthConfigView view = oauthService.getConfig(service, "byo",
                RequestContext.get().getUserId());
        if (view == null) {
            throw new ConnectorException(ConnectorErrorCode.OAUTH_CONFIG_MISSING,
                    "未配置 BYO App，将回退使用平台公共 App");
        }
        return ReqResult.success(view);
    }

    /**
     * 空间自建连接器的共享 OAuth App 配置（scopeType=platform, ownerId=0）：
     * 创建者填写后空间内所有用户授权时共用同一个 App。官方目录（space_id=0）
     * 的公共 App 走管理侧 /api/system/connector/oauth-config（MCP_DEV 门），两条写路径互斥。
     */
    @PostMapping("/shared-config")
    @Operation(summary = "保存空间连接器的共享 OAuth App 配置（创建者填写，全空间共用；client_secret 加密落库）")
    public ReqResult<Void> saveSharedConfig(@Valid @RequestBody OauthConfigSaveDto dto) {
        ConnectorProvider provider = requireSpaceOauthProvider(dto.getService());
        spacePermissionService.checkSpaceAdminPermission(provider.getSpaceId());
        oauthService.saveConfig(dto.getService(), "platform", 0L,
                dto.getClientId(), dto.getClientSecret(), dto.getAuthUrl(), dto.getTokenUrl(), dto.getScopes());
        return ReqResult.success();
    }

    @GetMapping("/shared-config")
    @Operation(summary = "查看空间连接器的共享 OAuth App 配置（client_secret 脱敏；未配置返回 null）")
    public ReqResult<ConnectorOAuthService.OauthConfigView> getSharedConfig(@RequestParam String service) {
        ConnectorProvider provider = requireSpaceOauthProvider(service);
        spacePermissionService.checkSpaceUserPermission(provider.getSpaceId());
        return ReqResult.success(oauthService.getConfig(service, "platform", 0L));
    }

    private ConnectorProvider requireSpaceOauthProvider(String service) {
        ConnectorProvider provider = catalogService.requireProvider(service);
        if (provider.getSpaceId() == null || provider.getSpaceId() == 0L) {
            throw new ConnectorException(ConnectorErrorCode.PROTECTED_ZONE,
                    "官方目录连接器的公共 App 配置请通过管理侧接口维护: " + service);
        }
        return provider;
    }

    // ---------- 扫码建连（设备码流程，authType=oauth2_device） ----------

    @GetMapping("/device/authorize")
    @Operation(summary = "发起扫码授权：返回 state、userCode 与二维码地址（授权 URL 本身不下发）")
    public ReqResult<DeviceAuthorizeVo> deviceAuthorize(@RequestParam String service,
                                                        @RequestParam(required = false) Long spaceId) {
        ConnectorProvider provider = catalogService.requireProvider(service);
        // 与 GET /authorize 同一套官方/空间解析：官方连接器全局可见、无空间归属，不校验 spaceId；
        // null 仅作「跳过权限检查」哨兵，连接最终落 spaceId=0（对齐官方语义）
        Long effectiveSpaceId = provider.isOfficial() ? null : spaceId;
        if (effectiveSpaceId != null) {
            spacePermissionService.checkSpaceUserPermission(effectiveSpaceId);
        }
        ConnectorOAuthService.DeviceBeginView view = oauthService.beginDeviceFlow(service, effectiveSpaceId);
        // state 是 Base64url 无填充（字符集只有 [A-Za-z0-9_-]），直接拼进查询串即安全，
        // 不必再 URL 编码——多编一层反而可能让前端拿到 %3D 之类的东西去比对 state。
        String qrUrl = "/api/connector/oauth/device/qr?state=" + view.state();
        return ReqResult.success(new DeviceAuthorizeVo(view.mode(), view.state(), view.userCode(),
                view.expiresIn(), view.interval(), qrUrl));
    }

    /**
     * 按 state 渲染二维码 PNG。
     * <p>
     * 待编码文本来自自己库里的 state 行（解密 {@code redirect_target}），<b>绝不来自请求参数</b>。
     * 这是不复用平台既有 {@code /api/qr/{base64text}} 的核心理由：那个端点会把任意文本变成二维码，
     * 等于对外开放一个 QR oracle。这里 state 只是取行句柄，且要过归属与有效期校验。
     * <p>
     * 不消费 state：轮询期间前端可能重复请求这张图（抽屉重开、img 重新加载）。
     */
    @GetMapping(value = "/device/qr", produces = MediaType.IMAGE_PNG_VALUE)
    @Operation(summary = "按 state 渲染扫码二维码（PNG）")
    public void deviceQr(@RequestParam String state, HttpServletResponse response)
            throws WriterException, IOException {
        String url = oauthService.deviceVerificationUrl(state);
        Map<EncodeHintType, Object> hints = Map.of(
                EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name(),
                EncodeHintType.MARGIN, 1);
        BitMatrix matrix = new MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE, hints);
        // 顺序要紧：把所有可能失败的动作（取行、解密、编码）都做完，再动 response。
        // 一旦开始写流就改不回错误响应了，半截 PNG 会让前端 <img> 显示破图而不是报出真实原因。
        response.setContentType(MediaType.IMAGE_PNG_VALUE);
        response.setHeader("Cache-Control", "no-store");
        MatrixToImageWriter.writeToStream(matrix, "PNG", response.getOutputStream());
    }

    @PostMapping("/device/poll")
    @Operation(summary = "轮询扫码结果：pending 继续等；authorized/denied/expired/already_completed/failed 为终态")
    public ReqResult<DevicePollVo> devicePoll(@Valid @RequestBody DevicePollDto dto) {
        ConnectorOAuthService.DevicePollView view = oauthService.pollDeviceFlow(dto.getState(), dto.getInterval());
        return ReqResult.success(new DevicePollVo(view.status(), view.interval(), view.connectionId(),
                view.displayName(), view.message()));
    }

    // ---------- 完成页 ----------

    private String renderCompletionPage(ConnectorOAuthService.CallbackOutcome outcome) {
        boolean success = outcome.success();
        String title = success ? "连接成功" : "连接失败";
        String providerLine = outcome.displayName() == null ? ""
                : "<p class=\"provider\">" + escape(outcome.displayName()) + "</p>";
        String icon = success ? "&#10004;" : "&#10008;";
        String color = success ? "#16a34a" : "#dc2626";
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>%s</title>
                <style>
                body { font-family: -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif;
                       background: #f5f6f8; margin: 0; }
                .card { max-width: 420px; margin: 12vh auto; background: #fff; border-radius: 12px;
                        padding: 40px 32px; text-align: center; box-shadow: 0 4px 24px rgba(0,0,0,.06); }
                .icon { font-size: 48px; color: %s; line-height: 1; }
                h1 { font-size: 20px; margin: 16px 0 8px; color: #111827; }
                .provider { color: #6b7280; margin: 0 0 8px; }
                .message { color: #4b5563; font-size: 14px; }
                .hint { color: #9ca3af; font-size: 12px; margin-top: 24px; }
                .btn { margin-top: 20px; padding: 10px 24px; font-size: 14px; font-family: inherit;
                       color: #fff; background: #111827; border: 0; border-radius: 8px; cursor: pointer; }
                .btn:hover { background: #1f2937; }
                .tip { display: none; color: #9ca3af; font-size: 12px; margin-top: 10px; line-height: 1.6; }
                </style>
                </head>
                <body>
                <div class="card">
                <div class="icon">%s</div>
                <h1>%s</h1>
                %s
                <p class="message">%s</p>
                <p class="hint">可以关闭此页面，回到女娲继续操作。</p>
                <button type="button" class="btn" id="closeBtn">关闭此窗口</button>
                <p class="tip" id="closeTip">浏览器不允许网页自行关闭本页，请手动关闭（Mac 上按 &#8984;+W）。</p>
                </div>
                <script>
                // window.close() 只对「由脚本打开的窗口」生效：用户直接点授权链接、或被 IdP 重定向过来的
                // 标签页属于浏览器历史里的正常页面，关不掉。所以关完延时探一下——页面还在就亮出提示，
                // 免得按钮看起来毫无反应。
                document.getElementById('closeBtn').addEventListener('click', function () {
                    window.close();
                    setTimeout(function () {
                        document.getElementById('closeTip').style.display = 'block';
                    }, 300);
                });
                </script>
                </body>
                </html>
                """.formatted(title, color, icon, title, providerLine, escape(outcome.message()));
    }

    private String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
