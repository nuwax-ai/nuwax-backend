package com.xspaceagi.system.web.controller;

import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.dto.permission.MenuNodeDto;
import com.xspaceagi.system.application.service.AuthService;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.application.service.impl.SysUserPermissionCacheServiceImpl;
import com.xspaceagi.system.infra.verify.VerifyCodeSendAndCheckService;
import com.xspaceagi.system.infra.verify.captcha.CaptchaConfig;
import com.xspaceagi.system.infra.verify.email.SmtpConfig;
import com.xspaceagi.system.infra.verify.sms.SmsConfig;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.PageQueryVo;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.CodeTypeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.web.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Date;
import java.util.List;

@Slf4j
@Tag(name = "用户相关接口", description = "错误码：4010为未认证")
@RestController
@RequestMapping("/api/user")
public class UserController {

    @Resource
    private UserApplicationService userApplicationService;

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
        verifyCodeSendAndCheckService.checkCaptchaVerifyParam(buildCaptchaConfig(), loginDto.getCaptchaVerifyParam());
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

    @Operation(summary = "发送验证码")
    @RequestMapping(path = "/code/send", method = RequestMethod.POST)
    public ReqResult<Void> sendCode(@RequestBody CodeSendDto codeSendDto) {
        if (!RequestContext.get().isLogin()) {
            verifyCodeSendAndCheckService.checkCaptchaVerifyParam(buildCaptchaConfig(), codeSendDto.getCaptchaVerifyParam());
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
            throw new BizException("系统未配置短信服务，请直接输出本次验证码：" + code);
        }
        if (tenantConfigDto.getAuthType() == TenantConfigDto.AuthTypeEnum.EMAIL.getCode() && StringUtils.isBlank(tenantConfigDto.getSmtpUsername())) {
            throw new BizException("系统未配置邮件服务，请直接输出本次验证码：" + code);
        }
        return ReqResult.success();
    }

    @Operation(summary = "检测邮箱是否被占用")
    @RequestMapping(path = "/email/check/{email}", method = RequestMethod.POST)
    public ReqResult<Boolean> checkEmail(@PathVariable String email) {
        //验证邮箱格式，邮箱用户名支持 .
        if (!email.matches("^[a-zA-Z0-9._-]+@[a-zA-Z0-9_-]+(\\.[a-zA-Z0-9_-]+)+$")) {
            throw new BizException("邮箱格式不正确");
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
                throw new BizException("邮箱格式不正确");
            }
            verifyCodeSendAndCheckService.checkEmailCode(CodeTypeEnum.BIND_EMAIL, bindEmailDto.getEmail(), bindEmailDto.getCode());
            boolean isUsed = null != userApplicationService.queryUserByEmail(bindEmailDto.getEmail());
            if (isUsed) {
                throw new BizException("邮箱已被占用");
            }
        } else if (StringUtils.isNotBlank(bindEmailDto.getPhone())) {
            verifyCodeSendAndCheckService.checkPhoneCode(CodeTypeEnum.BIND_EMAIL, bindEmailDto.getPhone(), bindEmailDto.getCode());
            boolean isUsed = null != userApplicationService.queryUserByPhone(bindEmailDto.getPhone());
            if (isUsed) {
                throw new BizException("手机号已被占用");
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
        return ReqResult.success((UserDto) RequestContext.get().getUser());
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
            userDto.setStatus(null);
            userDto.setRole(null);
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
        PageQueryVo<com.xspaceagi.system.application.dto.UserQueryDto> pageQueryVo = new PageQueryVo<>();
        pageQueryVo.setPageNo(1L);
        pageQueryVo.setPageSize(1L);
        com.xspaceagi.system.application.dto.UserQueryDto userQueryDto1 = new com.xspaceagi.system.application.dto.UserQueryDto();
        userQueryDto1.setUserName(userQueryDto.getKw());
        pageQueryVo.setQueryFilter(userQueryDto1);
        List<UserDto> records = userApplicationService.listQuery(pageQueryVo).getRecords();
        records.forEach(userDto -> {
            if (StringUtils.isNotBlank(userDto.getNickName())) {
                userDto.setUserName(userDto.getNickName() + "(" + userDto.getUserName() + ")");
            }
            userDto.setPassword(null);
            userDto.setPhone(null);
            userDto.setEmail(null);
            userDto.setStatus(null);
            //此处需要返回role，用于限制用户是否能绑定角色
            //userDto.setRole(null);
        });
        //records大于五条时，只返回前五条
        if (records.size() > 5) {
            records = records.subList(0, 5);
        }
        return ReqResult.success(records);
    }

    @Operation(summary = "查询用户的菜单权限（树形结构）")
    @GetMapping("/list-menu")
    public ReqResult<List<MenuNodeDto>> getMenuList() {
        Long userId = ((UserDto) RequestContext.get().getUser()).getId();
        if (userId == null) {
            return ReqResult.error("用户未登录");
        }
        List<MenuNodeDto> menuTree = sysUserAuthCacheService.getUserMenuTree(userId);
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
        return captchaConfig;
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
}