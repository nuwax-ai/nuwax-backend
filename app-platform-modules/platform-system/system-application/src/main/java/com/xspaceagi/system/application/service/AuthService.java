package com.xspaceagi.system.application.service;


import com.xspaceagi.system.application.dto.UserDto;

import java.util.List;

public interface AuthService {

    String createToken(UserDto userDto, String clientId);

    String loginWithCode(String emailOrPhone, String code);

    String loginWithMpCode(String code);

    String loginWithPassword(String emailOrPhone, String password);

    UserDto getLoginUserInfo(String token);

    String refreshToken(String token);

    void expireToken(String token);

    void renewToken(String token);

    void expireUserAllToken(Long userId);

    String getClientId(Long userId, String token);

    List<String> getUserClientIds(Long userId);

    String newTicket(UserDto userDto, String token);

    String getTokenByTicket(String ticket);
}
