package com.xspaceagi.system.application.service.impl;

import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.AuthService;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.infra.rpc.WeChatMpService;
import com.xspaceagi.system.infra.verify.VerifyCodeSendAndCheckService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.CodeTypeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.utils.JwtUtils;
import com.xspaceagi.system.spec.utils.RedisUtil;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class AuthServiceImpl implements AuthService {

    @Resource
    private UserApplicationService userApplicationService;

    @Resource
    private VerifyCodeSendAndCheckService verifyCodeSendAndCheckService;

    @Resource
    private WeChatMpService weChatMpService;

    @Resource
    private RedisUtil redisUtil;

    @Value("${jwt.secretKey}")
    private String jwtSecretKey;

    @Override
    public String loginWithCode(String emailOrPhone, String code) {
        Assert.notNull(emailOrPhone, "emailOrPhone must be non-null");
        UserDto userDto;
        String phone = null;
        String email = null;
        //验证邮箱格式
        if (emailOrPhone.matches("^[a-zA-Z0-9._-]+@[a-zA-Z0-9_-]+(\\.[a-zA-Z0-9_-]+)+$")) {
            verifyCodeSendAndCheckService.checkEmailCode(CodeTypeEnum.LOGIN_OR_REGISTER, emailOrPhone, code);
            userDto = userApplicationService.queryUserByEmail(emailOrPhone);
            email = emailOrPhone;
        } else {
            verifyCodeSendAndCheckService.checkPhoneCode(CodeTypeEnum.LOGIN_OR_REGISTER, emailOrPhone, code);
            userDto = userApplicationService.queryUserByPhone(emailOrPhone);
            phone = emailOrPhone;
        }
        if (userDto == null) {
            TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
            if (tenantConfigDto.getOpenRegister() != null && tenantConfigDto.getOpenRegister() == 0) {
                throw new BizException("注册已关闭");
            }
            userDto = new UserDto();
            userDto.setPhone(phone);
            userDto.setEmail(email);
            userApplicationService.add(userDto);
        }
        if (userDto.getStatus() == User.Status.Disabled) {
            throw new BizException("账号已被禁用");
        }
        updateLastLoginTime(userDto);
        return createToken(userDto, UUID.randomUUID().toString().replace("-", ""));
    }

    @Override
    public String loginWithMpCode(String code) {
        //获取小程序用户手机号
        String phone = weChatMpService.getPhoneNumber(code);
        UserDto userDto = userApplicationService.queryUserByPhone(phone);
        if (userDto == null) {
            TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
            if (tenantConfigDto.getOpenRegister() != null && tenantConfigDto.getOpenRegister() == 0) {
                throw new BizException("注册已关闭");
            }
            userDto = new UserDto();
            userDto.setPhone(phone);
            userApplicationService.add(userDto);
        }
        if (userDto.getStatus() == User.Status.Disabled) {
            throw new BizException("账号已被禁用");
        }
        updateLastLoginTime(userDto);
        return createToken(userDto, UUID.randomUUID().toString().replace("-", ""));
    }

    private void updateLastLoginTime(UserDto userDto) {
        UserDto update = new UserDto();
        update.setId(userDto.getId());
        update.setLastLoginTime(new Date());
        userApplicationService.update(update);
    }

    public String createToken(UserDto userDto, String clientId) {
        //过期token检查
        Map<String, Object> map = redisUtil.hashGetAll("user-token:" + userDto.getId());
        //不能无限膨胀，一个账号同时最多100个token
        if (map.size() > 100) {
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                String key = entry.getKey();
                redisUtil.expire("token:" + key, 0);
                redisUtil.hashDelete("user-token:" + userDto.getId(), key);
            }
        } else {
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                String key = entry.getKey();
                if (redisUtil.get("token:" + key) == null) {
                    redisUtil.hashDelete("user-token:" + userDto.getId(), key);
                }
            }
        }
        TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
        int expire = (int) (tenantConfigDto.getAuthExpire() == null ? 86400 : tenantConfigDto.getAuthExpire() * 60);
        String token = JwtUtils.createJwt(String.valueOf(userDto.getId()), userDto.getPhone(), jwtSecretKey, expire, new HashMap<>());
        redisUtil.set("token:" + token, userDto.getId().toString(), expire);
        redisUtil.hashPut("user-token:" + userDto.getId(), token, clientId);
        redisUtil.expire("user-token:" + userDto.getId(), expire);
        return token;
    }

    @Override
    public String loginWithPassword(String emailOrPhone, String password) {
        Assert.notNull(emailOrPhone, "emailOrPhone must be non-null");
        UserDto userDto = userApplicationService.queryUserByPhoneOrEmailWithPassword(emailOrPhone, password);
        if (userDto != null) {
            updateLastLoginTime(userDto);
            return createToken(userDto, UUID.randomUUID().toString().replace("-", ""));
        }
        throw new BizException("用户不存在或密码错误");
    }

    @Override
    public UserDto getLoginUserInfo(String token) {
        if (token == null) {
            return null;
        }
        Object val = redisUtil.get("token:" + token);
        if (val == null) {
            return null;
        }
        try {
            Long userId = Long.valueOf(val.toString());
            return userApplicationService.queryById(userId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public String refreshToken(String token) {
        UserDto userDto = getLoginUserInfo(token);
        if (userDto != null) {
            // 设置原来的token 10秒后过期
            redisUtil.expire("token:" + token, 10);
            String clientId = getClientId(userDto.getId(), token);
            redisUtil.hashDelete("user-token:" + userDto.getId(), token);
            String newToken = createToken(userDto, clientId);
            Object parentToken = redisUtil.get("token-parent:" + token);
            if (parentToken != null) {
                TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
                int expire = (int) (tenantConfigDto.getAuthExpire() == null ? 86400 : tenantConfigDto.getAuthExpire() * 60);
                redisUtil.set("token-sub:" + parentToken, newToken, expire);
                redisUtil.set("token-parent:" + newToken, parentToken.toString(), expire);
                redisUtil.expire("token-parent:" + token, 0);
            }
            return newToken;
        }
        return token;
    }

    @Override
    public void expireToken(String token) {
        Object val = redisUtil.get("token:" + token);
        if (val != null) {
            redisUtil.hashDelete("user-token:" + val, token);
        }
        redisUtil.expire("token:" + token, 0);
    }

    @Override
    public void renewToken(String token) {
        TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
        int expire = (int) (tenantConfigDto.getAuthExpire() == null ? 86400 : tenantConfigDto.getAuthExpire() * 60);
        redisUtil.expire("token:" + token, expire);
    }

    @Override
    public void expireUserAllToken(Long userId) {
        Map<String, Object> map = redisUtil.hashGetAll("user-token:" + userId);
        if (map != null) {
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                redisUtil.expire("token:" + entry.getKey(), 0);
            }
        }
        redisUtil.expire("user-token:" + userId, 0);
    }

    @Override
    public String getClientId(Long userId, String token) {
        Object val = redisUtil.hashGet("user-token:" + userId, token);
        if (val == null || val.toString().isEmpty()) {
            val = UUID.randomUUID().toString().replace("-", "");
            redisUtil.hashPut("user-token:" + userId, token, val);
        }
        return val.toString();
    }

    @Override
    public List<String> getUserClientIds(Long userId) {
        Map<String, Object> map = redisUtil.hashGetAll("user-token:" + userId);
        if (map != null) {
            return map.values().stream().map(Object::toString).collect(Collectors.toList());
        }
        return List.of();
    }

    @Override
    public String newTicket(UserDto userDto, String token) {
        Object subToken = redisUtil.get("token-sub:" + token);
        if (subToken == null) {
            String clientId = getClientId(userDto.getId(), token);
            subToken = createToken(userDto, clientId);
            TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
            int expire = (int) (tenantConfigDto.getAuthExpire() == null ? 86400 : tenantConfigDto.getAuthExpire() * 60);
            redisUtil.set("token-parent:" + subToken, token, expire - 60);
            redisUtil.set("token-sub:" + token, subToken.toString(), expire - 60);
        }
        String ticket = UUID.randomUUID().toString().replace("-", "");
        redisUtil.set("ticket:" + ticket, subToken.toString(), 60);// 60秒后过期
        return ticket;
    }

    @Override
    public String getTokenByTicket(String ticket) {
        Object val = redisUtil.get("ticket:" + ticket);
        if (val != null) {
            redisUtil.expire("ticket:" + ticket, 0);
            return val.toString();
        }
        return null;
    }
}
