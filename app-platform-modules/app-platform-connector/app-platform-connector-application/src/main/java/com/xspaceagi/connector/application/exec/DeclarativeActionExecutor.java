package com.xspaceagi.connector.application.exec;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.connector.domain.dto.ExecResultDto;
import com.xspaceagi.connector.domain.entity.ConnectorAction;
import com.xspaceagi.connector.domain.entity.ConnectorProvider;
import com.xspaceagi.connector.infra.http.GuardedHttpClient;
import com.xspaceagi.connector.spec.error.ConnectorErrorCode;
import com.xspaceagi.connector.spec.exception.ConnectorException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 声明式 HTTP 动作执行器（路径 A）。
 * <p>
 * http_spec 结构（存 connector_action.http_spec）：
 * <pre>
 * {
 *   "method": "POST",
 *   "path": "/repos/{owner}/{repo}/issues",
 *   "query": {"per_page": "pageSize"},
 *   "headers": {"X-Custom": "fieldName"},
 *   "body": {"title": "title"},           // JSON 体（扁平，存量格式）：key=输出字段，value=输入字段名
 *   "bodyFields": [                       // JSON 体（类型化嵌套，与 body 二选一，优先于 body）：
 *     {"name": "title", "type": "string", "mapping": "title"},
 *     {"name": "count", "type": "number", "mapping": "opt:count"},
 *     {"name": "meta",  "type": "object", "children": [ ...同构节点... ]},
 *     {"name": "items", "type": "array",  "mapping": "items",
 *      "item": {"type": "object", "children": [ ... ]}}   // item 仅结构声明，不参与取值
 *   ],
 *   "bodyRaw": "rawPayload",              // 与 body/bodyFields 互斥且优先级最高：原样发送 args[rawPayload]
 *   "timeoutMs": 30000,
 *   "response": {"extract": "$.data.items"}
 * }
 * </pre>
 * 映射值语义：输入字段名；{@code literal:xxx} 前缀表示字面量；{@code opt:xxx} 前缀表示
 * 可选入参——缺失时该 key 整体省略（不发送），用于上游"省略字段=默认行为"的可选参数
 * （如根目录操作须省略 folder_id）。无前后缀的映射缺失输入字段报 EXEC_INPUT_INVALID。
 * path 占位符 {name} 从输入取值并 URL 编码。
 * <p>
 * bodyFields 语义：type ∈ string/number/boolean/object/array。object 由 children 递归构建
 * （空 children 输出 {}）；array 的 mapping 指向提供整个数组的输入参数（整体透传，
 * item 只声明元素结构、运行期不消费）；叶子按类型校正（number/boolean 校正失败即报错，
 * 不静默降级）。存量 action 无 bodyFields 键时行为与旧版完全一致。
 */
@Slf4j
@Service
public class DeclarativeActionExecutor {

    private static final Pattern PATH_VAR = Pattern.compile("\\{([a-zA-Z0-9_]+)}");
    /**
     * extract 路径片段：键名允许字母数字下划线与连字符（HTTP 响应常见 total-count/X-Request-Id）、
     * 数组下标 {@code [n]}、数组通配 {@code [*]}。
     */
    private static final Pattern SEGMENT = Pattern.compile("([a-zA-Z0-9_-]+)|\\[(\\d+)]|\\[\\*]");
    /** 路径 token 里的通配标记，与键名（String）、下标（Integer）区分 */
    private static final Object WILDCARD = new Object();
    /** navigate 的「这一环没匹配上」哨兵：与「匹配到了、值就是 null」区分开，后者在通配收集时会被保留 */
    private static final Object NO_MATCH = new Object();
    private static final String LITERAL_PREFIX = "literal:";
    private static final String OPT_PREFIX = "opt:";

    @Resource
    private GuardedHttpClient guardedHttpClient;

    /**
     * 执行声明式动作。上游非 2xx / 超时 / SSRF 拦截等抛 {@link ConnectorException}，
     * 由编排层落审计并转信封。
     */
    public ExecResultDto execute(ConnectorProvider provider, ConnectorAction action,
                                 Map<String, String> authHeaders, Map<String, String> authQuery,
                                 Map<String, Object> args) {
        JSONObject spec = parseSpec(action);
        Map<String, Object> safeArgs = args == null ? Map.of() : args;

        String method = StringUtils.defaultIfBlank(spec.getString("method"), "GET")
                .toUpperCase(Locale.ROOT);
        String path = renderPath(spec.getString("path"), safeArgs);
        Map<String, String> headers = new LinkedHashMap<>();
        if (authHeaders != null) {
            headers.putAll(authHeaders);
        }
        Map<String, String> query = new LinkedHashMap<>();
        if (authQuery != null) {
            query.putAll(authQuery);
        }
        JSONObject specQuery = spec.getJSONObject("query");
        if (specQuery != null) {
            for (String name : specQuery.keySet()) {
                putMapped(query, name, specQuery.getString(name), safeArgs);
            }
        }
        JSONObject specHeaders = spec.getJSONObject("headers");
        if (specHeaders != null) {
            for (String name : specHeaders.keySet()) {
                putMapped(headers, name, specHeaders.getString(name), safeArgs);
            }
        }

        byte[] body = null;
        String bodyRawField = spec.getString("bodyRaw");
        Object bodySpec = spec.get("body");
        JSONArray bodyFields = spec.getJSONArray("bodyFields");
        if (StringUtils.isNotBlank(bodyRawField)) {
            Object raw = requireArg(bodyRawField, safeArgs);
            body = String.valueOf(raw).getBytes(StandardCharsets.UTF_8);
            headers.putIfAbsent("Content-Type", "application/json; charset=utf-8");
        } else if (bodyFields != null && !bodyFields.isEmpty()) {
            JSONObject payload = buildTypedObject(bodyFields, safeArgs, "body");
            body = payload.toJSONString().getBytes(StandardCharsets.UTF_8);
            headers.putIfAbsent("Content-Type", "application/json; charset=utf-8");
        } else if (bodySpec instanceof JSONObject bodyObj) {
            JSONObject payload = new JSONObject();
            for (String name : bodyObj.keySet()) {
                Object mapping = bodyObj.get(name);
                String mappingStr = mapping == null ? null : String.valueOf(mapping);
                Object value = mappingStr == null ? null : resolveValue(mappingStr, safeArgs);
                if (value == null && mappingStr != null && mappingStr.startsWith(OPT_PREFIX)) {
                    continue; // 可选入参缺失：该 key 整体省略
                }
                payload.put(name, value);
            }
            body = payload.toJSONString().getBytes(StandardCharsets.UTF_8);
            headers.putIfAbsent("Content-Type", "application/json; charset=utf-8");
        }

        String url = buildUrl(provider.getBaseUrl(), path, query);
        long timeoutMs = spec.getLongValue("timeoutMs", 0L);

        GuardedHttpClient.GuardedHttpResponse response =
                guardedHttpClient.execute(method, url, headers, body, timeoutMs);

        return buildResult(action, spec, response);
    }

    // ---------- 请求构建 ----------

    private JSONObject parseSpec(ConnectorAction action) {
        if (StringUtils.isBlank(action.getHttpSpec())) {
            throw new ConnectorException(ConnectorErrorCode.EXEC_INPUT_INVALID,
                    "声明式动作缺少 http_spec: " + action.getActionKey());
        }
        try {
            return JSON.parseObject(action.getHttpSpec());
        } catch (Exception e) {
            throw new ConnectorException(ConnectorErrorCode.EXEC_INPUT_INVALID,
                    "http_spec 非法 JSON: " + action.getActionKey(), e);
        }
    }

    private String renderPath(String path, Map<String, Object> args) {
        if (StringUtils.isBlank(path)) {
            return "/";
        }
        Matcher matcher = PATH_VAR.matcher(path);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String field = matcher.group(1);
            Object value = requireArg(field, args);
            matcher.appendReplacement(rendered,
                    Matcher.quoteReplacement(encodePathSegment(String.valueOf(value))));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private String resolveString(String mapping, Map<String, Object> args) {
        return String.valueOf(resolveValue(mapping, args));
    }

    private Object resolveValue(String mapping, Map<String, Object> args) {
        if (mapping == null) {
            return null;
        }
        if (mapping.startsWith(LITERAL_PREFIX)) {
            return mapping.substring(LITERAL_PREFIX.length());
        }
        if (mapping.startsWith(OPT_PREFIX)) {
            return args.get(mapping.substring(OPT_PREFIX.length()));
        }
        return requireArg(mapping, args);
    }

    /** query/header 映射写入：opt: 映射入参缺失时跳过，其余保持缺失即报错。 */
    private void putMapped(Map<String, String> target, String name, String mapping, Map<String, Object> args) {
        Object value = resolveValue(mapping, args);
        if (value == null && mapping != null && mapping.startsWith(OPT_PREFIX)) {
            return;
        }
        target.put(name, String.valueOf(value));
    }

    private Object requireArg(String field, Map<String, Object> args) {
        Object value = args.get(field);
        if (value == null) {
            throw new ConnectorException(ConnectorErrorCode.EXEC_INPUT_INVALID, "缺少输入参数: " + field);
        }
        return value;
    }

    // ---------- 类型化 body（bodyFields）递归构建 ----------

    /**
     * 按类型化字段树构建 JSON 对象。节点 type ∈ string/number/boolean/object/array；
     * object 递归 children；array 整体取自 mapping 指向的输入参数（item 仅结构声明不消费）；
     * 叶子按类型校正。opt: 映射缺失时该 key 整体省略。错误消息携带字段路径（如 body.items.qty）。
     */
    private JSONObject buildTypedObject(JSONArray fields, Map<String, Object> args, String path) {
        JSONObject obj = new JSONObject();
        for (int i = 0; i < fields.size(); i++) {
            JSONObject node = fields.getJSONObject(i);
            String name = node == null ? null : node.getString("name");
            if (StringUtils.isBlank(name)) {
                throw execInvalid("bodyFields[" + i + "] 缺少 name: " + path);
            }
            String type = StringUtils.defaultIfBlank(node.getString("type"), "string")
                    .toLowerCase(Locale.ROOT);
            String childPath = path + "." + name;
            switch (type) {
                case "object" -> {
                    JSONArray children = node.getJSONArray("children");
                    obj.put(name, children == null || children.isEmpty()
                            ? new JSONObject()
                            : buildTypedObject(children, args, childPath));
                }
                case "array" -> {
                    String mapping = node.getString("mapping");
                    if (StringUtils.isBlank(mapping)) {
                        throw execInvalid("array 字段缺少 mapping: " + childPath);
                    }
                    Object value = resolveValue(mapping, args);
                    if (value == null && mapping.startsWith(OPT_PREFIX)) {
                        break; // 可选入参缺失：该字段整体省略
                    }
                    obj.put(name, toJsonArray(value, childPath));
                }
                case "string", "number", "boolean" -> {
                    String mapping = node.getString("mapping");
                    if (StringUtils.isBlank(mapping)) {
                        throw execInvalid("字段缺少 mapping: " + childPath);
                    }
                    Object value = resolveValue(mapping, args);
                    if (value == null && mapping.startsWith(OPT_PREFIX)) {
                        break; // 可选入参缺失：该 key 整体省略
                    }
                    obj.put(name, coerceLeaf(value, type, childPath));
                }
                default -> throw execInvalid("不支持的字段类型: " + type + " (" + childPath + ")");
            }
        }
        return obj;
    }

    /** 输入值归一为 JSONArray：已是数组直接返回；Collection 转换；JSON 数组字符串解析；其余报错。 */
    private JSONArray toJsonArray(Object value, String path) {
        if (value instanceof JSONArray array) {
            return array;
        }
        if (value instanceof Collection<?> collection) {
            JSONArray array = new JSONArray();
            array.addAll(collection);
            return array;
        }
        if (value instanceof String text && text.trim().startsWith("[")) {
            try {
                return JSON.parseArray(text);
            } catch (Exception e) {
                throw execInvalid("array 字段值不是合法 JSON 数组: " + path);
            }
        }
        throw execInvalid("array 字段值不是数组: " + path);
    }

    /** 叶子类型校正：number/boolean 校正失败即报错（不静默降级），string 与扁平路径 String.valueOf 一致。 */
    private Object coerceLeaf(Object value, String type, String path) {
        switch (type) {
            case "number" -> {
                if (value instanceof Number number) {
                    return number;
                }
                String text = String.valueOf(value).trim();
                try {
                    return (text.indexOf('.') < 0 && text.indexOf('e') < 0 && text.indexOf('E') < 0)
                            ? (Object) Long.parseLong(text) : (Object) Double.parseDouble(text);
                } catch (NumberFormatException e) {
                    throw execInvalid("number 字段无法解析为数字: " + path);
                }
            }
            case "boolean" -> {
                if (value instanceof Boolean bool) {
                    return bool;
                }
                String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
                if ("true".equals(text)) {
                    return Boolean.TRUE;
                }
                if ("false".equals(text)) {
                    return Boolean.FALSE;
                }
                throw execInvalid("boolean 字段只接受 true/false: " + path);
            }
            default -> {
                return String.valueOf(value);
            }
        }
    }

    private ConnectorException execInvalid(String message) {
        return new ConnectorException(ConnectorErrorCode.EXEC_INPUT_INVALID, message);
    }

    private String buildUrl(String baseUrl, String path, Map<String, String> query) {
        if (StringUtils.isBlank(baseUrl)) {
            throw new ConnectorException(ConnectorErrorCode.EXEC_INPUT_INVALID, "provider 缺少 base_url");
        }
        StringBuilder url = new StringBuilder(baseUrl.replaceAll("/+$", ""));
        if (!path.startsWith("/")) {
            url.append('/');
        }
        url.append(path);
        if (!query.isEmpty()) {
            url.append('?');
            boolean first = true;
            for (Map.Entry<String, String> entry : query.entrySet()) {
                if (!first) {
                    url.append('&');
                }
                url.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
                first = false;
            }
        }
        return url.toString();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * 路径段编码：空格必须写成 %20。URLEncoder 是 form-urlencoded 语义（空格→"+"），
     * 而路径里的 "+" 是字面加号，上游会当加号处理——传文件名/标题类路径参数时值就错了。
     * 真正的加号字符被 URLEncoder 编码为 %2B，所以下面的替换只会命中空格。query 仍用 encode。
     */
    private String encodePathSegment(String value) {
        return encode(value).replace("+", "%20");
    }

    // ---------- 响应处理 ----------

    private ExecResultDto buildResult(ConnectorAction action, JSONObject spec,
                                      GuardedHttpClient.GuardedHttpResponse response) {
        String bodyText = response.bodyAsString();
        Object parsed = tryParseJson(bodyText);
        int status = response.status();

        if (status >= 200 && status < 300) {
            // 响应体不是 JSON（HTML/XML/纯文本等）时兜底原始文本：与代理路径 responseToData 一致。
            // 否则 extract 拿到 null 直接返回，调用方看到的 success=true + data=null 无从分辨是空响应还是解析失败。
            Object data = parsed != null ? extract(parsed, spec) : responseToData(bodyText);
            return ExecResultDto.ok(data, "上游返回 " + status)
                    .meta("httpStatus", status)
                    .meta("durationMs", response.durationMs());
        }

        // 非 2xx：把上游错误体带回（Agent 排障需要），但截断
        Object errorData = parsed != null ? parsed : preview(bodyText);
        throw new ConnectorException(ConnectorErrorCode.UPSTREAM_ERROR,
                "上游返回 " + status + " (" + action.getActionKey() + ")",
                Map.of("httpStatus", status, "upstreamBody", errorData));
    }

    private Object tryParseJson(String bodyText) {
        if (StringUtils.isBlank(bodyText)) {
            return null;
        }
        try {
            return JSON.parse(bodyText);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 按 response.extract 抽取（支持 $.a.b[0].c、$[0].id、$.a[*].b；缺省整体）。
     * <p>
     * 键名允许字母数字下划线与连字符，数组下标用 {@code [n]}，数组通配用 {@code [*]}。路径未被
     * 完整消费（出现无法识别的字符）时返回 null——旧实现此时返回**已匹配前缀键的值**，于是
     * {@code $.total-count} 会静默取到键 {@code total} 的值：错值而非空值，是最难排查的一类。
     */
    private Object extract(Object parsed, JSONObject spec) {
        JSONObject responseSpec = spec.getJSONObject("response");
        String extractPath = responseSpec == null ? "$" : StringUtils.defaultIfBlank(
                responseSpec.getString("extract"), "$");
        if (parsed == null) {
            return null;
        }
        if ("$".equals(extractPath)) {
            return parsed;
        }
        // 剥掉根标记 $ 与紧随的点：$[0].id 这类顶层数组路径此前因 $ 未被剥离而整条解析失败
        String path = extractPath.startsWith("$") ? extractPath.substring(1) : extractPath;
        if (path.startsWith(".")) {
            path = path.substring(1);
        }
        List<Object> tokens = parsePath(path);
        if (tokens == null) {
            return null; // 含解析不了的字符：定位不到，不返回前缀键的值
        }
        Object result = navigate(parsed, tokens, 0);
        return result == NO_MATCH ? null : result;
    }

    /**
     * 把 extract 路径切成 token 列表（键名 String / 下标 Integer / {@link #WILDCARD}）。
     * 返回 null 表示路径没有被完整消费——即含无法识别的字符。
     */
    private List<Object> parsePath(String path) {
        List<Object> tokens = new ArrayList<>();
        Matcher matcher = SEGMENT.matcher(path);
        int lastEnd = 0;
        while (matcher.find()) {
            if (matcher.start() != lastEnd && !path.substring(lastEnd, matcher.start()).equals(".")) {
                return null;
            }
            lastEnd = matcher.end();
            if (matcher.group(1) != null) {
                tokens.add(matcher.group(1));
            } else if (matcher.group(2) != null) {
                tokens.add(Integer.parseInt(matcher.group(2)));
            } else {
                tokens.add(WILDCARD);
            }
        }
        return lastEnd == path.length() ? tokens : null;
    }

    /**
     * 沿 token 逐级取值。返回 {@link #NO_MATCH} 表示这一环断了；返回 null 表示取到的值本身就是 null。
     * <p>
     * {@code [*]} 把剩余路径应用到数组的**每个元素**，收集所有走得通的值组成数组，走不通的元素直接
     * 跳过——所以 {@code content[*].image} 能自动避开 content 里没有 image 的元素，正是「目标元素
     * 下标不固定」时该用的写法。多层通配不拍平，是一层套一层（{@code a[*].b[*]} → 二维数组）。
     */
    private Object navigate(Object current, List<Object> tokens, int idx) {
        if (idx == tokens.size()) {
            return current;
        }
        Object token = tokens.get(idx);
        if (token == WILDCARD) {
            if (!(current instanceof JSONArray array)) {
                return NO_MATCH;
            }
            JSONArray matched = new JSONArray();
            for (int i = 0; i < array.size(); i++) {
                Object value = navigate(array.get(i), tokens, idx + 1);
                if (value != NO_MATCH) {
                    matched.add(value);
                }
            }
            return matched.isEmpty() ? NO_MATCH : matched;
        }
        if (token instanceof Integer index) {
            if (!(current instanceof JSONArray array) || index >= array.size()) {
                return NO_MATCH;
            }
            return navigate(array.get(index), tokens, idx + 1);
        }
        String key = (String) token;
        if (!(current instanceof JSONObject obj) || !obj.containsKey(key)) {
            return NO_MATCH;
        }
        return navigate(obj.get(key), tokens, idx + 1);
    }

    private String preview(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 500 ? text : text.substring(0, 500) + "...(truncated)";
    }

    /**
     * provider auth_config.custom 注入规则：{"inject":[{"from":"ak","to":"query:access_key"}]}。
     * to 支持 header:名称 / query:名称。
     */
    public static void applyCustomInject(JSONObject authConfig, JSONObject credential,
                                         Map<String, String> authHeaders, Map<String, String> authQuery) {
        JSONArray inject = authConfig == null ? null : authConfig.getJSONArray("inject");
        if (inject == null) {
            return;
        }
        for (int i = 0; i < inject.size(); i++) {
            JSONObject rule = inject.getJSONObject(i);
            String from = rule.getString("from");
            String to = rule.getString("to");
            String value = credential.getString(from);
            if (StringUtils.isBlank(from) || StringUtils.isBlank(to) || value == null) {
                continue;
            }
            int colon = to.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String target = to.substring(0, colon).toLowerCase(Locale.ROOT);
            String name = to.substring(colon + 1);
            if ("header".equals(target)) {
                authHeaders.put(name, value);
            } else if ("query".equals(target)) {
                authQuery.put(name, value);
            }
        }
    }

    /**
     * 供代理路径复用的上游响应转数据（JSON 则解析，否则 {raw: text}）。
     */
    public static Object responseToData(String bodyText) {
        if (StringUtils.isBlank(bodyText)) {
            return null;
        }
        try {
            return JSON.parse(bodyText);
        } catch (Exception e) {
            JSONObject raw = new JSONObject();
            raw.put("raw", bodyText.length() <= 2000 ? bodyText : bodyText.substring(0, 2000) + "...(truncated)");
            return raw;
        }
    }

    public static List<String> allowedProxyMethods() {
        return List.of("GET", "POST", "PUT", "PATCH", "DELETE");
    }
}
