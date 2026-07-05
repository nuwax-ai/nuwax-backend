package com.xspaceagi.system.application.util;

import com.xspaceagi.file.sdk.IFileAccessService;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.spec.common.RequestContext;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;

@Component
public class DefaultIconUrlUtil {

    private static IFileAccessService iFileAccessService;

    @Autowired
    public void setIFileAccessService(IFileAccessService iFileAccessService) {
        DefaultIconUrlUtil.iFileAccessService = iFileAccessService;
    }

    public static String setDefaultIconUrl(String originalIcon, String name) {
        return setDefaultIconUrl(originalIcon, name, "default");
    }

    public static String setDefaultIconUrl(String originalIcon, String name, String type) {
        if (StringUtils.isBlank(name) || RequestContext.get() == null) {
            return originalIcon;
        }
        //避免名字太长引起url超长
        if (name.length() > 16) {
            name = name.substring(0, 16);
        }
        TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
        if (tenantConfigDto != null && (StringUtils.isBlank(originalIcon) || originalIcon.contains("api/logo"))) {
            //siteUrl去除/
            String siteUrl = tenantConfigDto.getSiteUrl().trim().endsWith("/") ? tenantConfigDto.getSiteUrl() : tenantConfigDto.getSiteUrl() + "/";
            try {
                return siteUrl + "api/logo/" + type.toLowerCase() + "/" + URLEncoder.encode(name.replace("/", ""), "UTF-8");
            } catch (Exception e) {
            }
        }
        return originalIcon;
    }

    public static String setDefaultIconUrl(String originalIcon, String name, String type, boolean returnOriginalUrl) {
        return iFileAccessService.getFileUrlWithAk(setDefaultIconUrl(originalIcon, name, type), returnOriginalUrl);
    }

    public static String removeDefaultIconUrl(String url) {
        if (url == null || url.contains("/api/logo/")) {
            return null;
        }
        return url;
    }
}
