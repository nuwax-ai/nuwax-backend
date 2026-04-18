package com.xspaceagi.system.web.controller;

import com.xspaceagi.system.application.constant.I18nLangTagConstraints;
import com.xspaceagi.system.application.constant.SupportedLocaleConstants;
import com.xspaceagi.system.application.dto.I18nLangDto;
import com.xspaceagi.system.application.dto.SupportedLocaleOptionDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.I18nApplicationService;
import com.xspaceagi.system.application.service.I18nLangApplicationService;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 国际化管理器
 */
@Slf4j
@Tag(name = "多语言查询", description = "国际化相关接口（语言管理、多语言配置）")
@RestController
@RequestMapping("/api/i18n")
public class I18nController {

    @Resource
    private I18nApplicationService i18nApplicationService;

    @Resource
    private I18nLangApplicationService i18nLangApplicationService;

    @Resource
    private UserApplicationService userApplicationService;

    /**
     * 查询系统语言 Map
     */
    @Operation(summary = "查询指定语言信息")
    @GetMapping(value = "/query", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Map<String, String>> querySystemLangMap(@RequestParam(value = "side", required = false) String side,
                                                             @RequestParam(value = "lang", required = false) String lang,
                                                             HttpServletResponse response) {
        log.info("[querySystemLangMap] 查询系统语言 Map, lang={}", lang);
        String resolved = StringUtils.isBlank(lang) ? RequestContext.get().getLang() : lang.trim();
        lang = StringUtils.isBlank(resolved) ? resolved
                : I18nLangTagConstraints.tryNormalizeToStoredForm(resolved).orElse(resolved);
        Map<String, String> map = i18nApplicationService.querySystemLangMap(RequestContext.get().getTenantId(), side, lang);

        if (StringUtils.isBlank(lang)) {
            return ReqResult.success(map);
        }

        if (RequestContext.get().isLogin()) {
            UserDto update = new UserDto();
            update.setLang(lang);
            update.setId(RequestContext.get().getUserId());
            userApplicationService.update(update);
            UserDto userDto = (UserDto) RequestContext.get().getUser();
            if (userDto.getRole() != User.Role.Admin) {
                removeSystemManageMessage(map);
            }
        } else {
            removeSystemManageMessage(map);
        }
        Cookie cookie = new Cookie("lang", lang);
        cookie.setMaxAge(86400 * 365);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        response.addCookie(cookie);
        return ReqResult.success(map);
    }

    private void removeSystemManageMessage(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return;
        }
        // 先收集需要删除的 key，避免在遍历过程中直接修改 map
        List<String> keysToRemove = map.keySet().stream()
                .filter(key -> key.startsWith("PC.System")) // TODO 确认管理端的 key前缀
                .toList();
        // 批量删除
        keysToRemove.forEach(map::remove);
    }

    @Operation(summary = "查询语言列表（用户侧：不展示租户默认，仅标当前会话语言）")
    @GetMapping(value = "/lang/list", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<List<I18nLangDto>> queryLangList() {
        List<I18nLangDto> i18nLangs = i18nLangApplicationService.queryAll();
        
        String currentLang = RequestContext.get().getLang();
        if (StringUtils.isNotBlank(currentLang)) {
            //不把租户默认当默认展示，统一先置为非默认
            i18nLangs.forEach(l -> l.setIsDefault(0));
        
            i18nLangs.stream()
                    .filter(l -> l.getLang() != null && l.getLang().equalsIgnoreCase(currentLang))
                    .findFirst()
                    .ifPresent(l -> l.setIsDefault(1));
        }
        return ReqResult.success(i18nLangs);
    }
}
