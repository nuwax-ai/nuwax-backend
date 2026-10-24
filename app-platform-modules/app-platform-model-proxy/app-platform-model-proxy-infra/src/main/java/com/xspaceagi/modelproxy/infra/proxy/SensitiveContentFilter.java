package com.xspaceagi.modelproxy.infra.proxy;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * 模型请求体敏感词内容过滤。
 * <p>
 * 只对内容字段应用过滤，协议字段（role/model/name 等）一律不动。
 * 不能对整个请求体 JSON 做盲替换：敏感词若是协议枚举值的子串（如 eve 命中 developer），
 * 会把 "role":"developer" 改写成非法值，上游反序列化直接 422。
 */
public class SensitiveContentFilter {

    /**
     * 承载用户与系统文本的字段名，覆盖主流协议：
     * OpenAI Chat 的 messages[].content（string 或 text part 数组）、
     * Responses 的 instructions 与 input（string 形式或 item 数组的 input_text）、
     * embeddings/moderations 的 input、legacy completions 的 prompt、
     * Anthropic 的 system 与 content[].text、Gemini 的 parts[].text。
     * 内容 key 下的数组，其裸 string 元素同样视为内容（string 形式的 input/content）。
     * 协议新增字段只要沿用这些 key 即自动覆盖，其余 key 一律视为协议字段不处理。
     */
    private static final Set<String> CONTENT_KEYS = Set.of("content", "text", "input_text", "instructions", "system", "input", "prompt");

    /**
     * 递归遍历 JSON 树，仅对内容字段的字符串值应用过滤器，原地修改
     *
     * @param node   JSON 节点（JSONObject 或 JSONArray）
     * @param filter 文本过滤器，如触发断开策略应抛异常由调用方处理
     */
    public static void filter(Object node, UnaryOperator<String> filter) {
        if (node instanceof JSONObject object) {
            for (String key : object.keySet()) {
                Object value = object.get(key);
                if (value instanceof String text) {
                    if (CONTENT_KEYS.contains(key) && !text.isBlank()) {
                        // 仅替换已有 key 的值，遍历中不新增节点，fastjson2 下安全
                        object.put(key, filter.apply(text));
                    }
                } else if (value instanceof JSONArray array && CONTENT_KEYS.contains(key)) {
                    filterContentArray(array, filter);
                } else if (value instanceof JSONObject || value instanceof JSONArray) {
                    filter(value, filter);
                }
            }
            return;
        }
        if (node instanceof JSONArray array) {
            for (Object element : array) {
                filter(element, filter);
            }
        }
    }

    /**
     * 内容 key 下的数组：裸 string 元素是内容本身（如 "input":["文本1","文本2"]、
     * "content":["文本"]），直接过滤；容器元素回到通用递归
     */
    private static void filterContentArray(JSONArray array, UnaryOperator<String> filter) {
        for (int i = 0; i < array.size(); i++) {
            Object element = array.get(i);
            if (element instanceof String text && !text.isBlank()) {
                array.set(i, filter.apply(text));
            } else if (element instanceof JSONObject || element instanceof JSONArray) {
                filter(element, filter);
            }
        }
    }
}
