package com.xspaceagi.mcp.spec.utils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UrlExtractUtil {

    public static List<String> extractUrls(String text) {
        if (JSON.isValid(text)) {
            JSONObject jsonObject = JSON.parseObject(text);
            String url = parseUrl(jsonObject);
            if (url != null) {
                return List.of(url);
            }
        }
        if (text == null) {
            return List.of();
        }
        List<String> urls = new ArrayList<>();
        String regex = "(https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(text);

        while (matcher.find()) {
            urls.add(matcher.group());
        }

        return urls;
    }

    public static Map<String, String> extractHeaders(String serverConfig) {
        if (JSON.isValid(serverConfig)) {
            JSONObject jsonObject = JSON.parseObject(serverConfig);
            return extractHeaders(jsonObject);
        }
        return null;
    }

    private static Map<String, String> extractHeaders(JSONObject jsonObject) {
        if (jsonObject == null) {
            return null;
        }
        for (String key : jsonObject.keySet()) {
            Object value = jsonObject.get(key);
            if (key.toLowerCase().equals("headers")) {
                if (value instanceof Map) {
                    Map<String, String> map = (Map<String, String>) value;
                    ((Map<?, ?>) value).forEach((k, v) -> {
                        map.put(k.toString(), v == null ? "" : v.toString());
                    });
                    return map;
                }
            } else if (value instanceof JSONObject) {
                Map<String, String> headers = extractHeaders((JSONObject) value);
                if (headers != null) {
                    return headers;
                }
            }
        }
        return null;
    }


    private static String parseUrl(JSONObject jsonObject) {
        for (String key : jsonObject.keySet()) {
            Object value = jsonObject.get(key);
            String url = parseValue(value);
            if (url != null) {
                return url;
            }
        }
        return null;
    }

    private static String parseValue(Object value) {
        if (value instanceof String) {
            if (value != null && value.toString().startsWith("http://") || value.toString().startsWith("https://")) {
                return value.toString();
            }
        }
        if (value instanceof JSONObject) {
            return parseUrl((JSONObject) value);
        }
        if (value instanceof JSONArray) {
            return parseUrlFromArray((JSONArray) value);
        }
        return null;
    }

    private static String parseUrlFromArray(JSONArray value) {
        for (int i = 0; i < value.size(); i++) {
            Object item = value.get(i);
            String url = parseValue(item);
            if (url != null) {
                return url;
            }
        }
        return null;
    }
}
