package com.xspaceagi.system.spec.utils;

import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Objects;
import java.util.UUID;

@Component
public class FileAkUtil {

    @Resource
    private RedisUtil redisUtil;

    @Value("${storage.type}")
    private String storageType;

    public String getFileUrlWithAk(String fileUrl) {
        if (fileUrl == null) {
            return null;
        }
        if (!"file".equals(storageType) || !fileUrl.contains("/api/file") || fileUrl.contains("ak=")) {
            return fileUrl;
        }
        String path;
        try {
            path = getUrlPath(fileUrl);
        } catch (Exception e) {
            return fileUrl;
        }
        Object ak = redisUtil.get("file.ak:" + path);
        if (Objects.nonNull(ak)) {
            return fileUrl + "?ak=" + ak;
        }
        ak = UUID.randomUUID().toString().replace("-", "");
        redisUtil.set("file.ak:" + path, ak.toString(), 60 * 60 * 24);
        return fileUrl + "?ak=" + ak;
    }


    public void checkFileUrlAk(String uri, String ak) {
        if (!"file".equals(storageType) && uri.contains("/api/file")) {
            return;
        }

        Object ak0 = redisUtil.get("file.ak:" + uri);
        if (ak0 == null || !ak0.equals(ak)) {
            throw new IllegalArgumentException("Invalid file URL");
        }
    }

    private static String getUrlPath(String fileUrl) throws MalformedURLException {
        URL url;
        try {
            url = new URL(fileUrl);
        } catch (MalformedURLException e) {
            throw e;
        }
        return url.getPath();
    }
}
