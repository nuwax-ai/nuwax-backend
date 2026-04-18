package com.xspaceagi.system.web.controller;

import com.xspaceagi.system.sdk.service.UserAccessKeyApiService;
import com.xspaceagi.system.sdk.service.dto.UserAccessKeyDto;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.HttpStatusEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class ChatKeyCheck {

    public static void check(HttpServletRequest request, UserAccessKeyApiService userAccessKeyApiService) {
        String fragment = request.getHeader("Fragment");
        String referer = request.getHeader("Referer");
        String chatKey = null;
        if (referer != null && referer.contains("chat-temp")) {
            try {
                URL url = new URL(referer);
                chatKey = url.getPath().replace("chat-temp", "").replace("/", "");
            } catch (Exception e) {
                //
                log.warn("referer error", e);
            }
        } else if (StringUtils.isNotBlank(fragment)) {
            //fragment：chatKey=ck-1ce5a050c8f64f5a8b555d06b4818038
            //用正则获取chatKey的值，其中 chatKey后面为：-或其他字符串
            chatKey = extractChatKey(fragment);
        }
        if (StringUtils.isNotBlank(chatKey)) {
            UserAccessKeyDto userAccessKeyDto = userAccessKeyApiService.queryAccessKey(chatKey);
            if (userAccessKeyDto == null) {
                throw BizException.of(HttpStatusEnum.UNAUTHORIZED, ErrorCodeEnum.UNAUTHORIZED,
                        BizExceptionCodeEnum.systemUnauthorizedOrSessionExpired);
            }
        }
    }

    private static String extractChatKey(String fragment) {
        // 正则表达式提取chatKey
        String regex = "chatKey=([^&]+)";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(fragment);

        if (matcher.find()) {
            return matcher.group(1);
        } else {
            return null;
        }
    }
}
