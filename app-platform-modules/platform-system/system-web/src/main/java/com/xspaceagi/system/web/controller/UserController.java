package com.xspaceagi.system.web.controller;

import com.xspaceagi.file.sdk.IFileAccessService;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.dto.permission.MenuNodeDto;
import com.xspaceagi.system.application.service.AuthService;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.application.service.impl.SysUserPermissionCacheServiceImpl;
import com.xspaceagi.system.domain.service.UserDomainService;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.infra.verify.VerifyCodeSendAndCheckService;
import com.xspaceagi.system.infra.verify.captcha.CaptchaConfig;
import com.xspaceagi.system.infra.verify.captcha.ImageCaptchaDto;
import com.xspaceagi.system.infra.verify.email.SmtpConfig;
import com.xspaceagi.system.infra.verify.sms.SmsConfig;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.CodeTypeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.utils.I18nUtil;
import com.xspaceagi.system.spec.utils.IPUtil;
import com.xspaceagi.system.spec.utils.MD5;
import com.xspaceagi.system.web.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.*;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Slf4j
@Tag(name = "用户相关接口", description = "错误码：4010为未认证")
@RestController
@RequestMapping("/api/user")
public class UserController {

    @Resource
    private UserApplicationService userApplicationService;

    @Resource
    private UserDomainService userDomainService;

    @Resource
    private IFileAccessService iFileAccessService;

    @Resource
    private VerifyCodeSendAndCheckService verifyCodeSendAndCheckService;

    @Resource
    private SysUserPermissionCacheServiceImpl sysUserAuthCacheService;

    @Resource
    private SysUserPermissionCacheServiceImpl sysUserPermissionCacheService;

    @Resource
    private AuthService authService;

    @Operation(summary = "密码登录接口")
    @RequestMapping(path = "/passwordLogin", method = RequestMethod.POST)
    public ReqResult<LoginResDto> passwordLogin(@RequestBody PasswordLoginDto loginDto, HttpServletRequest request, HttpServletResponse response) {
        CaptchaConfig captchaConfig = buildCaptchaConfig();
        verifyCodeSendAndCheckService.checkCaptchaVerifyParam(captchaConfig, loginDto.getCaptchaVerifyParam());
        verifyCodeSendAndCheckService.checkImageCaptcha(captchaConfig, loginDto.getCaptchaId(), loginDto.getCaptchaCode());
        String token = authService.loginWithPassword(StringUtils.isBlank(loginDto.getPhone()) ? loginDto.getPhoneOrEmail() : loginDto.getPhone(), loginDto.getPassword());
        return ReqResult.success(buildLoginResDto(token, request, response));
    }

    @Operation(summary = "验证码登录/注册接口")
    @RequestMapping(path = "/codeLogin", method = RequestMethod.POST)
    public ReqResult<LoginResDto> codeLogin(@RequestBody CodeLoginDto loginDto, HttpServletRequest request, HttpServletResponse response) {
        String token = authService.loginWithCode(StringUtils.isBlank(loginDto.getPhone()) ? loginDto.getPhoneOrEmail() : loginDto.getPhone(), loginDto.getCode());
        return ReqResult.success(buildLoginResDto(token, request, response));
    }

    @Operation(summary = "小程序code登录")
    @RequestMapping(path = "/mp/codeLogin", method = RequestMethod.POST)
    public ReqResult<LoginResDto> mpCodeLogin(@RequestBody MPCodeLoginDto loginDto, HttpServletRequest request, HttpServletResponse response) {
        String token = authService.loginWithMpCode(loginDto.getCode());
        return ReqResult.success(buildLoginResDto(token, request, response));
    }

    @Operation(summary = "退出登录接口")
    @RequestMapping(path = "/logout", method = RequestMethod.GET)
    public ReqResult<Void> logout(HttpServletResponse response) {
        authService.expireToken(RequestContext.get().getToken());
        Cookie cookie = new Cookie("ticket", "");
        cookie.setMaxAge(-1);
        cookie.setPath("/");
        response.addCookie(cookie);
        return ReqResult.success();
    }

    @Operation(summary = "获取图形验证码")
    @RequestMapping(path = "/captcha/image", method = RequestMethod.GET)
    public ReqResult<ImageCaptchaDto> imageCaptcha(HttpServletRequest request) {
        return ReqResult.success(verifyCodeSendAndCheckService.createImageCaptcha(buildCaptchaConfig(), IPUtil.getIpAddr(request)));
    }

    @Operation(summary = "发送验证码")
    @RequestMapping(path = "/code/send", method = RequestMethod.POST)
    public ReqResult<Void> sendCode(@RequestBody CodeSendDto codeSendDto) {
        Assert.notNull(codeSendDto, "codeSendDto is null");
        Assert.notNull(codeSendDto.getType(), "type is null");
        if (!RequestContext.get().isLogin()) {
            CaptchaConfig captchaConfig = buildCaptchaConfig();
            verifyCodeSendAndCheckService.checkCaptchaVerifyParam(captchaConfig, codeSendDto.getCaptchaVerifyParam());
            verifyCodeSendAndCheckService.checkImageCaptcha(captchaConfig, codeSendDto.getCaptchaId(), codeSendDto.getCaptchaCode());
        }
        TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
        if (CodeSendDto.Type.RESET_PASSWORD == codeSendDto.getType()) {
            UserDto curUserDto = (UserDto) RequestContext.get().getUser();
            //3 为邮箱登录
            if (tenantConfigDto.getAuthType() == TenantConfigDto.AuthTypeEnum.EMAIL.getCode()) {
                codeSendDto.setEmail(curUserDto.getEmail());
            }
            if (tenantConfigDto.getAuthType() == TenantConfigDto.AuthTypeEnum.PHONE.getCode()) {
                codeSendDto.setPhone(curUserDto.getPhone());
            } else {
                codeSendDto.setEmail(curUserDto.getEmail());
            }
        }
        String code = null;
        if (StringUtils.isNotBlank(codeSendDto.getPhone())) {
            SmsConfig smsConfig = new SmsConfig();
            smsConfig.setSmsAccessKeyId(tenantConfigDto.getSmsAccessKeyId());
            smsConfig.setSmsAccessKeySecret(tenantConfigDto.getSmsAccessKeySecret());
            smsConfig.setSmsSignName(tenantConfigDto.getSmsSignName());
            smsConfig.setSmsTemplateCode(tenantConfigDto.getSmsTemplateCode());
            code = verifyCodeSendAndCheckService.sendPhoneCode(smsConfig, CodeTypeEnum.valueOf(codeSendDto.getType().name()), codeSendDto.getPhone());
        }
        if (StringUtils.isNotBlank(codeSendDto.getEmail())) {
            SmtpConfig stmpConfig = new SmtpConfig();
            stmpConfig.setHost(tenantConfigDto.getSmtpHost());
            stmpConfig.setPort(tenantConfigDto.getSmtpPort());
            stmpConfig.setUsername(tenantConfigDto.getSmtpUsername());
            stmpConfig.setPassword(tenantConfigDto.getSmtpPassword());
            stmpConfig.setSiteName(tenantConfigDto.getSiteName());
            code = verifyCodeSendAndCheckService.sendEmailCode(stmpConfig, CodeTypeEnum.valueOf(codeSendDto.getType().name()), codeSendDto.getEmail());
        }
        log.info("发送验证码:{},手机号:{},邮箱:{}", code, codeSendDto.getPhone(), codeSendDto.getEmail());
        if (tenantConfigDto.getAuthType() == TenantConfigDto.AuthTypeEnum.PHONE.getCode() && StringUtils.isBlank(tenantConfigDto.getSmsAccessKeyId())) {
            throw new BizException(I18nUtil.systemMessage("Backend.User.SendCode.SmsServiceNotConfigured", code));
        }
        if (tenantConfigDto.getAuthType() == TenantConfigDto.AuthTypeEnum.EMAIL.getCode() && StringUtils.isBlank(tenantConfigDto.getSmtpUsername())) {
            throw new BizException(I18nUtil.systemMessage("Backend.User.SendCode.EmailServiceNotConfigured", code));
        }
        return ReqResult.success();
    }

    @Operation(summary = "检测邮箱是否被占用")
    @RequestMapping(path = "/email/check/{email}", method = RequestMethod.POST)
    public ReqResult<Boolean> checkEmail(@PathVariable String email) {
        //验证邮箱格式，邮箱用户名支持 .
        if (!email.matches("^[a-zA-Z0-9._-]+@[a-zA-Z0-9_-]+(\\.[a-zA-Z0-9_-]+)+$")) {
            throw new BizException(I18nUtil.systemMessage("Backend.User.Email.InvalidFormat"));
        }
        boolean isUsed = null != userApplicationService.queryUserByEmail(email);
        return ReqResult.success(isUsed);
    }

    @Operation(summary = "绑定邮箱")
    @RequestMapping(path = "/email/bind", method = RequestMethod.POST)
    public ReqResult<Void> bindEmail(@RequestBody BindEmailDto bindEmailDto) {
        if (StringUtils.isNotBlank(bindEmailDto.getEmail())) {
            //验证邮箱格式
            if (!bindEmailDto.getEmail().matches("^[a-zA-Z0-9._-]+@[a-zA-Z0-9_-]+(\\.[a-zA-Z0-9_-]+)+$")) {
                throw new BizException(I18nUtil.systemMessage("Backend.User.Email.InvalidFormat"));
            }
            verifyCodeSendAndCheckService.checkEmailCode(CodeTypeEnum.BIND_EMAIL, bindEmailDto.getEmail(), bindEmailDto.getCode());
            boolean isUsed = null != userApplicationService.queryUserByEmail(bindEmailDto.getEmail());
            if (isUsed) {
                throw new BizException(I18nUtil.systemMessage("Backend.User.Email.Occupied"));
            }
        } else if (StringUtils.isNotBlank(bindEmailDto.getPhone())) {
            verifyCodeSendAndCheckService.checkPhoneCode(CodeTypeEnum.BIND_EMAIL, bindEmailDto.getPhone(), bindEmailDto.getCode());
            boolean isUsed = null != userApplicationService.queryUserByPhone(bindEmailDto.getPhone());
            if (isUsed) {
                throw new BizException(I18nUtil.systemMessage("Backend.User.Phone.Occupied"));
            }
        }
        UserDto userDto = (UserDto) RequestContext.get().getUser();
        UserDto userUpdate = new UserDto();
        userUpdate.setId(userDto.getId());
        userUpdate.setEmail(bindEmailDto.getEmail());
        userUpdate.setPhone(bindEmailDto.getPhone());
        userApplicationService.update(userUpdate);
        return ReqResult.success();
    }

    @Operation(summary = "重置密码")
    @RequestMapping(path = "/password/reset", method = RequestMethod.POST)
    public ReqResult<Void> resetPassword(@RequestBody ResetPasswordDto resetPasswordDto) {
        TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
        UserDto curUserDto = (UserDto) RequestContext.get().getUser();
        if ((tenantConfigDto.getAuthType() == 1 && StringUtils.isNotBlank(tenantConfigDto.getSmsAccessKeyId())) || tenantConfigDto.getAuthType() == 3 && StringUtils.isNotBlank(tenantConfigDto.getSmtpUsername())) {
            try {
                verifyCodeSendAndCheckService.checkPhoneCode(CodeTypeEnum.RESET_PASSWORD, curUserDto.getPhone(), resetPasswordDto.getCode());
            } catch (Exception e) {
                verifyCodeSendAndCheckService.checkEmailCode(CodeTypeEnum.RESET_PASSWORD, curUserDto.getEmail(), resetPasswordDto.getCode());
            }
        }
        UserDto userDto = (UserDto) RequestContext.get().getUser();
        UserDto userUpdate = new UserDto();
        userUpdate.setId(userDto.getId());
        userUpdate.setPassword(resetPasswordDto.getNewPassword());
        userApplicationService.update(userUpdate);
        return ReqResult.success();
    }

    @Operation(summary = "首次登录设置密码")
    @RequestMapping(path = "/password/set", method = RequestMethod.POST)
    public ReqResult<Void> setPassword(@RequestBody SetPasswordDto setPasswordDto) {
        UserDto userDto = (UserDto) RequestContext.get().getUser();
        if (userDto.getResetPass() == 0) {
            UserDto userUpdate = new UserDto();
            userUpdate.setId(userDto.getId());
            userUpdate.setPassword(setPasswordDto.getPassword());
            userUpdate.setResetPass(1);
            userApplicationService.update(userUpdate);
        }
        return ReqResult.success();
    }

    @Operation(summary = "更新用户信息")
    @RequestMapping(path = "/update", method = RequestMethod.POST)
    public ReqResult<Void> update(@RequestBody UserUpdateDto userUpdateDto) {
        UserDto userDto = (UserDto) RequestContext.get().getUser();
        UserDto userUpdate = new UserDto();
        userUpdate.setId(userDto.getId());
        userUpdate.setAvatar(userUpdateDto.getAvatar());
        userUpdate.setNickName(userUpdateDto.getNickName());
        userUpdate.setUserName(userUpdateDto.getUserName());
        userApplicationService.update(userUpdate);
        return ReqResult.success();
    }

    @Operation(summary = "查询当前登录用户信息")
    @RequestMapping(path = "/getLoginInfo", method = RequestMethod.GET)
    public ReqResult<UserDto> getLoginUserInfo() {
        UserDto user = (UserDto) RequestContext.get().getUser();
        user.setLangMap(null);
        return ReqResult.success(user);
    }

    @Operation(summary = "查询当前登录用户信息")
    @RequestMapping(path = "/info", method = RequestMethod.GET)
    public ReqResult<UserInfo> getLoginUserInfoForSandbox() {
        UserDto user = (UserDto) RequestContext.get().getUser();
        resetUserAvatarUrl((TenantConfigDto) RequestContext.get().getTenantConfig(), user);
        UserInfo userInfo = new UserInfo();
        BeanUtils.copyProperties(user, userInfo);
        return ReqResult.success(userInfo);
    }


    @Operation(summary = "获取当前登录用户的动态认证码")
    @RequestMapping(path = "/dynamicCode", method = RequestMethod.GET)
    public ReqResult<String> dynamicCode() {
        return ReqResult.success(userApplicationService.getUserDynamicCode(((UserDto) RequestContext.get().getUser()).getId()));
    }

    @Operation(summary = "查询用户信息")
    @RequestMapping(path = "/query", method = RequestMethod.POST)
    public ReqResult<UserDto> queryUser(@RequestBody UserInfoQueryDto userInfoQueryDto) {
        UserDto userDto = null;
        if (StringUtils.isNotBlank(userInfoQueryDto.getUserName())) {
            userDto = userApplicationService.queryUserByUserName(userInfoQueryDto.getUserName());
        } else if (StringUtils.isNotBlank(userInfoQueryDto.getEmail())) {
            userDto = userApplicationService.queryUserByEmail(userInfoQueryDto.getEmail());
        } else if (StringUtils.isNotBlank(userInfoQueryDto.getPhone())) {
            userDto = userApplicationService.queryUserByPhone(userInfoQueryDto.getPhone());
        }
        // 返回用户信息时，不返回密码、手机号、邮箱、状态、角色
        if (userDto != null) {
            userDto.setPassword(null);
            userDto.setPhone(null);
            userDto.setEmail(null);
            userDto.setRole(null);
            if (userDto.getStatus() == User.Status.Deleted) {
                return ReqResult.success(null);
            }
            userDto.setStatus(null);
        }
        return ReqResult.success(userDto);
    }

    @Operation(summary = "创建临时ticket")
    @RequestMapping(path = "/ticket/create", method = RequestMethod.POST)
    public ReqResult<String> createTicket(@RequestBody TicketCreateDto ticketCreateDto) {
        UserDto loginUserInfo = authService.getLoginUserInfo(ticketCreateDto.getToken());
        if (loginUserInfo != null) {
            return ReqResult.success(authService.newTicket(loginUserInfo, ticketCreateDto.getToken()));
        }
        return ReqResult.error("error auth token");
    }

    @Operation(summary = "根据关键字搜索用户信息")
    @RequestMapping(path = "/search", method = RequestMethod.POST)
    public ReqResult<List<UserDto>> search(@RequestBody UserQueryDto userQueryDto) {
        Assert.notNull(userQueryDto.getKw(), "kw cannot be null");
        UserDto userDto = null;
        if (userQueryDto.getKw().matches("^[a-zA-Z0-9._-]+@[a-zA-Z0-9_-]+(\\.[a-zA-Z0-9_-]+)+$")) {
            userDto = userApplicationService.queryUserByEmail(userQueryDto.getKw());
        }
        if (userDto == null) {
            userDto = userApplicationService.queryUserByPhone(userQueryDto.getKw());
            if (userDto == null) {
                userDto = userApplicationService.queryUserByUserName(userQueryDto.getKw());
            }
        }

        // 返回用户信息时，不返回密码、手机号、邮箱、状态、角色
        if (userDto != null) {
            userDto.setPassword(null);
            userDto.setPhone(null);
            userDto.setEmail(null);
            userDto.setRole(null);
            if (userDto.getStatus() == User.Status.Deleted) {
                return ReqResult.success(List.of());
            }
            return ReqResult.success(List.of(userDto));
        }
        return ReqResult.success(List.of());
    }

    @Operation(summary = "查询用户的菜单权限（树形结构）")
    @GetMapping("/list-menu")
    public ReqResult<List<MenuNodeDto>> getMenuList() {
        Long userId = ((UserDto) RequestContext.get().getUser()).getId();
        if (userId == null) {
            return ReqResult.error("用户未登录");
        }
        List<MenuNodeDto> menuTree = sysUserAuthCacheService.getUserMenuTree(userId);
        I18nUtil.replaceSystemMessage(menuTree);
        return ReqResult.success(menuTree);
    }

    @GetMapping("/refresh-permission")
    public ReqResult<Void> refreshPermission() {
        Long userId = ((UserDto) RequestContext.get().getUser()).getId();
        if (userId == null) {
            return ReqResult.error("用户未登录");
        }
        sysUserPermissionCacheService.clearCacheByUserIds(List.of(userId));
        return ReqResult.success();
    }

    @GetMapping("/lang")
    public ReqResult<Void> lang(HttpServletRequest request) {
        Long userId = ((UserDto) RequestContext.get().getUser()).getId();
        if (userId == null) {
            return ReqResult.error("用户未登录");
        }
        sysUserPermissionCacheService.clearCacheByUserIds(List.of(userId));
        return ReqResult.success();
    }

    private LoginResDto buildLoginResDto(String token, HttpServletRequest request, HttpServletResponse response) {
        TenantConfigDto tenantConfig = (TenantConfigDto) RequestContext.get().getTenantConfig();
        int expire = tenantConfig.getAuthExpire() == null ? 86400 * 30 : tenantConfig.getAuthExpire().intValue() * 60;
        Cookie cookie = new Cookie("ticket", token);
        cookie.setMaxAge(expire);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        response.addCookie(cookie);

        UserDto userDto = authService.getLoginUserInfo(token);
        String referer = request.getHeader("Referer");
        String redirect = extractRedirect(referer);
        String header = request.getHeader("X-Client-Type");
        if (!log.isDebugEnabled() && header == null) {
            token = "";
        }
        return LoginResDto.builder().resetPass(userDto.getResetPass()).redirect(redirect).expireDate(new Date(System.currentTimeMillis() + expire * 1000L)).token(token).build();
    }

    private CaptchaConfig buildCaptchaConfig() {
        TenantConfigDto tenantConfig = (TenantConfigDto) RequestContext.get().getTenantConfig();
        CaptchaConfig captchaConfig = new CaptchaConfig();
        captchaConfig.setCaptchaAccessKeyId(tenantConfig.getCaptchaAccessKeyId());
        captchaConfig.setCaptchaAccessKeySecret(tenantConfig.getCaptchaAccessKeySecret());
        captchaConfig.setOpenCaptcha(tenantConfig.getOpenCaptcha());
        captchaConfig.setOpenImageCaptcha(tenantConfig.getOpenImageCaptcha());
        return captchaConfig;
    }

    /**
     * 按用户 ID 重定向到该用户的真实头像地址。<b>免登录</b>（须登记在 {@code auth.excludePath}）。
     *
     * <p>存在意义：三方应用 / IM 卡片 / 邮件里往往只拿得到 userId，而头像字段存的可能是平台内文件
     * 路径（{@code /api/f/xxx}）—— 该路径对未登录调用者要求 {@code ak} 文件令牌，三方自己拼 URL 会
     * 401。这里统一换出「可直接访问」的地址再 302 过去；本来就是外链的头像原样透传。
     *
     * <p>安全口径（免登录接口，逐条对应实现）：
     * <ul>
     * <li><b>只认当前请求解析出的租户</b>：{@code UserDomainService.queryById} 是按主键查、带
     * {@code callWithIgnoreCheck}（不过滤 tenant_id），故此处显式比对租户；跨租户与用户不存在
     * 一律 404，且不区分两者，避免被当作用户存在性探测器；</li>
     * <li><b>只放行 http/https 绝对地址与站内相对路径</b>：挡掉 {@code javascript:}/{@code data:} 等
     * 协议，以及 {@code //host}（协议相对，等于跳外站）；</li>
     * <li><b>只读</b>：刻意不走 {@code UserApplicationService.queryById} —— 那条路径在用户 lang 为空时
     * 会顺手 UPDATE 一次 user.lang，公开接口不该带写副作用；</li>
     * <li>重定向地址含文件令牌，不写进日志。</li>
     * </ul>
     */
    @Operation(summary = "按用户 ID 重定向到头像地址（免登录）")
    @GetMapping("/avatar/{userId}/{md5}")
    public ResponseEntity<Void> avatar0(@PathVariable("userId") Long userId, @PathVariable("md5") String md5) {
        return avatar(userId);
    }

    @Operation(summary = "按用户 ID 重定向到头像地址（免登录）")
    @GetMapping("/avatar/{userId}")
    public ResponseEntity<Void> avatar(@PathVariable("userId") Long userId) {
        User user = userId == null ? null : userDomainService.queryById(userId);
        if (user == null || !Objects.equals(user.getTenantId(), currentTenantId())) {
            return ResponseEntity.notFound().build();
        }
        String target = StringUtils.trimToNull(resolveAvatarUrl(user));
        if (target == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
        } catch (IllegalArgumentException e) {
            // 历史脏数据里可能有含空格/中文未编码的头像值，取不到合法 URI 就当没有头像
            log.warn("头像地址不是合法 URI，userId={}", user.getId());
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 换出可直接访问的头像地址；拿不到合法地址返回 null。
     */
    private String resolveAvatarUrl(User user) {
        String siteUrl = currentSiteUrl();
        String avatar = StringUtils.trimToNull(user.getAvatar());
        if (avatar == null || avatar.contains("/api/logo/")) {
            // 没有头像，或存的是「按名字生成图标」的占位地址：指向平台默认图标接口，避免三方拿到 404 裂图
            return defaultAvatarUrl(siteUrl, user);
        }
        if (!isRedirectable(avatar)) {
            return null;
        }
        try {
            // withAk=true：/api/f/xxx 会换出带文件令牌的地址；外链不含 /api/f/，原样返回
            return iFileAccessService.getRealFileUrl(siteUrl, avatar, true, true);
        } catch (Exception e) {
            log.warn("解析头像地址失败，userId={}", user.getId());
            return null;
        }
    }

    /**
     * 默认头像：沿用 {@code DefaultIconUrlUtil} 的约定走 {@code /api/logo/{type}/{名字}}（该端点免登录）。
     */
    private static String defaultAvatarUrl(String siteUrl, User user) {
        String name = StringUtils.trimToNull(user.getNickName());
        if (name == null) {
            name = StringUtils.trimToNull(user.getUserName());
        }
        if (name == null) {
            name = "user";
        }
        // 与 DefaultIconUrlUtil 同一口径：截断避免 URL 超长，去掉斜杠避免多一级路径
        if (name.length() > 16) {
            name = name.substring(0, 16);
        }
        String base = siteUrl == null ? "" : siteUrl.replaceAll("/+$", "");
        try {
            return base + "/api/logo/user/" + URLEncoder.encode(name.replace("/", ""), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 只放行 http/https 绝对地址与站内相对路径。
     * <p>{@code //host} 是协议相对地址（浏览器会跳到外站），必须与站内路径区分开。
     */
    private static boolean isRedirectable(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return true;
        }
        return url.startsWith("/") && !url.startsWith("//");
    }

    private static Long currentTenantId() {
        RequestContext<Object> context = RequestContext.get();
        return context == null ? null : context.getTenantId();
    }

    private static String currentSiteUrl() {
        RequestContext<Object> context = RequestContext.get();
        if (context == null || context.getTenantConfig() == null) {
            return null;
        }
        return ((TenantConfigDto) context.getTenantConfig()).getSiteUrl();
    }

    private static String extractRedirect(String referer) {
        String redirect = null;
        if (StringUtils.isNotBlank(referer)) {
            try {
                String[] parts = referer.split("\\?");
                if (parts.length > 1) {
                    String queryString = parts[1];
                    String[] params = queryString.split("&");
                    for (String param : params) {
                        String[] keyValue = param.split("=");
                        if (keyValue.length == 2 && "redirect".equals(keyValue[0])) {
                            redirect = URLDecoder.decode(keyValue[1], "UTF-8");
                            break;
                        }
                    }
                }
            } catch (UnsupportedEncodingException e) {
            }
        }
        return redirect;
    }

    private void resetUserAvatarUrl(TenantConfigDto tenantConfigDto, UserDto user) {
        String siteUrl = tenantConfigDto.getSiteUrl();
        if (siteUrl.endsWith("/")) {
            siteUrl = siteUrl.substring(0, siteUrl.length() - 1);
        }
        user.setAvatar(siteUrl + "/api/user/avatar/" + user.getId() + (user.getAvatar() != null ? "/" + MD5.MD5Encode(user.getAvatar()) : ""));
    }
}