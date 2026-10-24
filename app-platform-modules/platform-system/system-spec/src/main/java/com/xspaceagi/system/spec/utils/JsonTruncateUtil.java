package com.xspaceagi.system.spec.utils;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.Map;

/**
 * JSON 长文本截断工具。
 * <p>
 * 递归遍历 {@link JSONObject} / {@link JSONArray}，将超过阈值的字符串值截断，
 * 保留头尾各若干字符，中间用 {@link #TRUNCATE_MARK} 代替。常用于把大体积的工具返回、
 * SSE 事件等写入日志/消息前的瘦身，避免超长文本撑爆下游。
 *
 * <pre>
 * JSONObject json = JSON.parseObject(eventData);
 * JsonTruncateUtil.truncate(json);                 // 默认：超 1024 截断，头尾各留 400
 * JsonTruncateUtil.truncate(json, 2048, 500);      // 自定义阈值与保留长度
 * </pre>
 */
public final class JsonTruncateUtil {

    /** 默认：字符串超过该长度才截断 */
    public static final int DEFAULT_THRESHOLD = 1024;
    /** 默认：截断时头尾各保留的字符数 */
    public static final int DEFAULT_KEEP = 400;
    /** 截断占位符 */
    public static final String TRUNCATE_MARK = "...[truncated]...";

    private JsonTruncateUtil() {
    }

    /**
     * 使用默认阈值（{@value #DEFAULT_THRESHOLD}）与保留长度（{@value #DEFAULT_KEEP}）截断。
     *
     * @param jsonObject 待处理的 JSON 对象，原地修改；为 null 时直接返回
     */
    public static void truncate(JSONObject jsonObject) {
        truncate(jsonObject, DEFAULT_THRESHOLD, DEFAULT_KEEP);
    }

    /**
     * 截断 JSON 中所有超长的字符串值（递归处理嵌套对象与数组）。
     *
     * @param jsonObject 待处理的 JSON 对象，原地修改；为 null 时直接返回
     * @param threshold  字符串超过该长度才截断
     * @param keep       截断时头尾各保留的字符数
     */
    public static void truncate(JSONObject jsonObject, int threshold, int keep) {
        if (jsonObject == null) {
            return;
        }
        truncateObject(jsonObject, threshold, keep);
    }

    /**
     * 截断单个字符串（核心逻辑）。长度不超过 threshold 时原样返回。
     *
     * @param str       原始字符串
     * @param threshold 超过该长度才截断
     * @param keep       头尾各保留的字符数
     * @return 截断后的字符串；入参为 null 时返回 null
     */
    public static String truncateString(String str, int threshold, int keep) {
        if (str == null || str.length() <= threshold) {
            return str;
        }
        return str.substring(0, keep) + TRUNCATE_MARK + str.substring(str.length() - keep);
    }

    /** 图片 base64 被截断后的占位符 */
    public static final String IMAGE_TRUNCATE_MARK = "[base64-image-truncated]";

    /**
     * 按「模型请求」语义截断，兼容 OpenAI / Anthropic 的请求体结构。
     * <p>
     * 与 {@link #truncate(JSONObject)} 的区别在于<b>区分角色</b>：
     * <ul>
     *   <li>系统提示词（Anthropic 顶层 {@code system}、OpenAI {@code role=system/developer}）—— 完全保留；</li>
     *   <li>用户提示词（{@code role=user}）—— 保留文本，仅压掉其中的图片 base64；</li>
     *   <li>其余角色（{@code assistant/tool/function}）—— 文本与图片 base64 一并截断。</li>
     * </ul>
     * 这样既能保留 system / user 提示词的可读性，又能干掉上下文里大量图片 base64
     * （Anthropic 的截图通常放在 {@code role=user} 的 {@code tool_result} 里，OpenAI 放在
     * {@code image_url.url} 的 data URI 里），从而降低内存占用。
     *
     * <p>注意：被压掉的图片 base64 会替换成占位符，不再是合法图片——适用于日志/持久化瘦身，
     * 或刻意丢弃历史截图以节省上下文；若要原样发图给模型请勿用本方法。
     *
     * @param request 模型请求体（含 {@code messages} 数组），原地修改；为 null 时直接返回
     */
    public static void truncateModelRequest(JSONObject request) {
        truncateModelRequest(request, DEFAULT_THRESHOLD, DEFAULT_KEEP);
    }

    /**
     * {@link #truncateModelRequest(JSONObject)} 的可配置阈值版本。
     *
     * @param request   模型请求体
     * @param threshold 文本超过该长度才截断（图片 base64 只要超过该长度即压掉）
     * @param keep       文本截断时头尾各保留的字符数
     */
    public static void truncateModelRequest(JSONObject request, int threshold, int keep) {
        if (request == null) {
            return;
        }
        // Anthropic 顶层 system（字符串或 block 数组）—— 完全保留，不处理
        JSONArray messages = request.getJSONArray("messages");
        if (messages == null) {
            return;
        }
        for (int i = 0; i < messages.size(); i++) {
            if (!(messages.get(i) instanceof JSONObject msg)) {
                continue;
            }
            if (isPromptRole(msg.getString("role"))) {
                // system / developer / user：保留文本，只压图片 base64
                truncateImagesOnly(msg, threshold);
            } else {
                // assistant / tool / function：文本 + 图片全部截断
                truncateObject(msg, threshold, keep);
            }
        }
    }

    /** system / developer / user 视为需要保留文本的提示词角色 */
    private static boolean isPromptRole(String role) {
        return "system".equals(role) || "developer".equals(role) || "user".equals(role);
    }

    /**
     * 只压图片 base64、保留其余文本。识别两种承载方式：
     * <ul>
     *   <li>Anthropic：{@code source.data}（{@code data} 键下的长字符串）；</li>
     *   <li>OpenAI：{@code image_url.url} 里 {@code data:...;base64,...} 形式的 data URI。</li>
     * </ul>
     */
    private static void truncateImagesOnly(Object node, int threshold) {
        if (node instanceof JSONObject obj) {
            for (Map.Entry<String, Object> entry : obj.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (value instanceof String str && str.length() > threshold) {
                    if ("data".equals(key)) {
                        // Anthropic 图片 source.data
                        entry.setValue(IMAGE_TRUNCATE_MARK);
                    } else if ("url".equals(key) && str.startsWith("data:")) {
                        // OpenAI 图片 image_url.url（data URI）
                        entry.setValue(truncateDataUri(str));
                    }
                    // 其余字符串（提示词文本等）保留不动
                } else if (value instanceof JSONObject || value instanceof JSONArray) {
                    truncateImagesOnly(value, threshold);
                }
            }
        } else if (node instanceof JSONArray arr) {
            for (int i = 0; i < arr.size(); i++) {
                Object value = arr.get(i);
                if (value instanceof JSONObject || value instanceof JSONArray) {
                    truncateImagesOnly(value, threshold);
                }
                // 数组里的裸字符串不处理（图片 base64 都挂在某个键下）
            }
        }
    }

    /** 保留 data URI 的头部（含媒体类型与 base64, 标记），仅替换真正的 base64 载荷 */
    private static String truncateDataUri(String uri) {
        int idx = uri.indexOf("base64,");
        if (idx >= 0) {
            return uri.substring(0, idx + "base64,".length()) + IMAGE_TRUNCATE_MARK;
        }
        return IMAGE_TRUNCATE_MARK;
    }

    private static void truncateObject(JSONObject jsonObject, int threshold, int keep) {
        for (Map.Entry<String, Object> entry : jsonObject.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String str) {
                String truncated = truncateString(str, threshold, keep);
                if (truncated != str) {
                    entry.setValue(truncated);
                }
            } else if (value instanceof JSONObject child) {
                truncateObject(child, threshold, keep);
            } else if (value instanceof JSONArray arr) {
                truncateArray(arr, threshold, keep);
            }
        }
    }

    private static void truncateArray(JSONArray array, int threshold, int keep) {
        for (int i = 0; i < array.size(); i++) {
            Object value = array.get(i);
            if (value instanceof String str) {
                String truncated = truncateString(str, threshold, keep);
                if (truncated != str) {
                    array.set(i, truncated);
                }
            } else if (value instanceof JSONObject child) {
                truncateObject(child, threshold, keep);
            } else if (value instanceof JSONArray arr) {
                truncateArray(arr, threshold, keep);
            }
        }
    }
}
