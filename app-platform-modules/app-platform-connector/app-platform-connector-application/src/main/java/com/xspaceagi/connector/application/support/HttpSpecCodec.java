package com.xspaceagi.connector.application.support;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.connector.spec.error.ConnectorErrorCode;
import com.xspaceagi.connector.spec.exception.ConnectorException;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * http_spec 编解码：建动作期做结构校验，并把校验过的规范形态落库。
 * <p>
 * <b>为什么需要它。</b>{@code inputArgs} 从有类型化结构起就一直在建动作时校验
 * （{@link ConnectorArgsCodec#validateTree}），而 {@code http_spec} 此前只是
 * {@code toJson} 序列化后直接落库，**一条都不校验**。于是同一份动作定义里，
 * 写错的入参当场被拒、写错的 httpSpec 却一路存进库，直到调用方真正执行时才炸——
 * 而且未必炸：下面几条在运行期是<b>静默</b>的。
 * <p>
 * 校验项全部取自 {@code DeclarativeActionExecutor} 的实际行为，只收「必然坏」的，
 * 不收「可疑」的（没有 warning 通道，宁缺勿滥）：
 * <ul>
 *   <li><b>type 枚举</b>：{@code buildTypedObject} 的 switch 只认
 *       string/number/boolean/object/array，其余走 {@code default ->} 抛
 *       EXEC_INPUT_INVALID。所以 {@code integer} 是「存得下、跑必挂」。</li>
 *   <li><b>object 节点带 mapping</b>：object 分支只读 {@code children}，从不读 {@code mapping}，
 *       写了也不报错——直接把映射丢掉，输出与预期不符。</li>
 *   <li><b>array / 叶子缺 mapping</b>：运行期取 mapping 为空即抛错。</li>
 *   <li><b>同级重名</b>：{@code obj.put(name, ...)} 后写覆盖先写，前一个节点是死配置。</li>
 *   <li><b>bodyRaw 带 {@code literal:}/{@code opt:} 前缀</b>：bodyRaw 走的是
 *       {@code requireArg(bodyRawField, args)}，<b>不经过</b> {@code resolveValue} 的前缀解析，
 *       于是 {@code opt:x} 被当成字面字段名，永远缺参。</li>
 *   <li><b>body / bodyFields / bodyRaw 三选一</b>：运行期按 bodyRaw &gt; bodyFields &gt; body
 *       取第一个非空，其余是死配置。同时写两种时，被忽略的那份不会有任何提示。</li>
 *   <li><b>query / headers 的值不是非空字符串</b>：运行期 {@code getString} 后当参数名用，
 *       数字/布尔/对象会被 String.valueOf 成 "100"/"true"/"{...}" 再去找同名入参，必然缺参。</li>
 * </ul>
 * <p>
 * <b>只校验「被消费的」那一支。</b>array 节点的 {@code item} 是<b>纯结构声明</b>，
 * 运行期完全不消费（见 {@code buildTypedObject} 的 array 分支），所以它的子树按
 * 「声明模式」校验——只看节点形状与 type 枚举，<b>不要求</b> name / mapping。
 * 把消费模式的规则（叶子必须有 mapping）套进去会误拒正确的定义。
 * <p>
 * <b>不校验的</b>：mapping 指向的输入参数是否在 {@code inputArgs} 里声明过。
 * {@code inputArgs} 在运行期只是文档（{@code args} 由调用方直接给），
 * 未声明≠不可用；拿它当判据会把「调用方临时多传一个参数」这种正常用法拒掉。
 */
public final class HttpSpecCodec {

    private HttpSpecCodec() {
    }

    /** 节点合法 type，与 {@code buildTypedObject} 的 switch 分支一一对应。 */
    private static final Set<String> NODE_TYPES =
            Set.of("string", "number", "boolean", "object", "array");
    private static final String LITERAL_PREFIX = "literal:";
    private static final String OPT_PREFIX = "opt:";

    /**
     * JSON Schema 类型名 → 本平台 bodyFields.type 的对应写法。
     * 只用于把报错写成「你该改成什么」，不参与判定。
     */
    private static final Map<String, String> TYPE_ALIASES = Map.ofEntries(
            Map.entry("integer", "number"), Map.entry("int", "number"), Map.entry("long", "number"),
            Map.entry("float", "number"), Map.entry("double", "number"), Map.entry("bigdecimal", "number"),
            Map.entry("str", "string"), Map.entry("text", "string"), Map.entry("date", "string"),
            Map.entry("bool", "boolean"), Map.entry("list", "array"), Map.entry("map", "object"),
            Map.entry("dict", "object"), Map.entry("jsonobject", "object"), Map.entry("jsonarray", "array"));

    /**
     * 校验并归一为落库用的 JSON 字符串。raw 为 null 时返回 null。
     *
     * @param raw   httpSpec（JSON 字符串 / Map / JSONObject）
     * @param where 报错位置描述，如 "action create_issue 的 httpSpec"
     */
    public static String normalize(Object raw, String where) {
        if (raw == null) {
            return null;
        }
        JSONObject spec = toObject(raw, where);
        checkBodyChoice(spec, where);
        checkBodyRaw(spec, where);
        checkBodyFields(spec, where);
        checkMappingMap(spec, "query", where);
        checkMappingMap(spec, "headers", where);
        checkResponse(spec, where);
        return spec.toJSONString();
    }

    private static JSONObject toObject(Object raw, String where) {
        Object parsed;
        try {
            parsed = raw instanceof String s ? JSON.parse(s) : JSON.toJSON(raw);
        } catch (Exception e) {
            throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR,
                    where + " 不是合法 JSON: " + e.getMessage());
        }
        if (!(parsed instanceof JSONObject obj)) {
            throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR,
                    where + " 需为对象结构 {method,path,query,headers,body|bodyFields|bodyRaw,response}");
        }
        return obj;
    }

    // ---------- body / bodyFields / bodyRaw ----------

    /**
     * 三选一。判定照抄运行期的取值顺序（bodyRaw &gt; bodyFields &gt; body），
     * 只要「会生效的」多于一个就拒——被忽略的那份在运行期没有任何提示。
     */
    private static void checkBodyChoice(JSONObject spec, String where) {
        Object raw = spec.get("bodyRaw");
        if (raw != null && !(raw instanceof String)) {
            throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR,
                    where + ".bodyRaw 必须是字符串（输入参数名），当前是 "
                            + raw.getClass().getSimpleName()
                            + "。运行期会把它 String.valueOf 后当字段名去找入参，必缺参");
        }
        JSONArray fields = bodyFields(spec, where);
        Object body = spec.get("body");
        if (body != null && !(body instanceof JSONObject)) {
            throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR,
                    where + ".body 必须是对象 {输出字段: 输入字段名}，当前是 "
                            + body.getClass().getSimpleName()
                            + "。运行期只在 body 是对象时才发送它，其他形态会被静默忽略（请求不带 body）");
        }

        List<String> active = new ArrayList<>();
        if (raw instanceof String s && StringUtils.isNotBlank(s)) {
            active.add("bodyRaw");
        }
        if (fields != null && !fields.isEmpty()) {
            active.add("bodyFields");
        }
        if (body instanceof JSONObject) {
            active.add("body");
        }
        if (active.size() > 1) {
            throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR,
                    where + " 同时写了 " + active + "，运行期按 bodyRaw > bodyFields > body 只取第一个，"
                            + "生效的是 " + active.get(0) + "，其余是死配置（不会有任何提示）。"
                            + "要发整个 JSON 体用 bodyRaw，要类型化嵌套用 bodyFields，扁平映射用 body——只留一种");
        }
    }

    /** bodyRaw 不经前缀解析，写成 opt:/literal: 会被当字面字段名，永远缺参。 */
    private static void checkBodyRaw(JSONObject spec, String where) {
        Object raw = spec.get("bodyRaw");
        if (!(raw instanceof String s) || StringUtils.isBlank(s)) {
            return;
        }
        if (s.startsWith(LITERAL_PREFIX) || s.startsWith(OPT_PREFIX)) {
            throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR,
                    where + ".bodyRaw 不能带 " + (s.startsWith(OPT_PREFIX) ? OPT_PREFIX : LITERAL_PREFIX)
                            + " 前缀（当前是 " + s + "）。bodyRaw 直接当输入参数名取值，不做前缀解析——"
                            + "前缀只有 body/bodyFields 的 mapping、query、headers 支持，写在这里必然缺参");
        }
    }

    /** 取 bodyFields；形态不对（非数组）直接拒，避免 getJSONArray 静默返回 null 当成「没写」。 */
    private static JSONArray bodyFields(JSONObject spec, String where) {
        Object value = spec.get("bodyFields");
        if (value == null) {
            return null;
        }
        JSONArray fields;
        try {
            fields = spec.getJSONArray("bodyFields");
        } catch (Exception e) {
            fields = null;
        }
        if (fields == null) {
            throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR,
                    where + ".bodyFields 必须是数组 [{name,type,mapping|children}]，当前是 "
                            + value.getClass().getSimpleName());
        }
        return fields;
    }

    private static void checkBodyFields(JSONObject spec, String where) {
        JSONArray fields = bodyFields(spec, where);
        if (fields != null) {
            checkNodes(fields, where + ".bodyFields", true);
        }
    }

    /**
     * 递归校验节点树。
     *
     * @param consumed true=被运行期消费的一支（要求 name 与 mapping）；false=仅结构声明
     *                 （array 的 item 子树），只查形状与 type 枚举
     */
    private static void checkNodes(JSONArray nodes, String path, boolean consumed) {
        Set<String> names = new HashSet<>();
        for (int i = 0; i < nodes.size(); i++) {
            Object value = nodes.get(i);
            String at = path + "[" + i + "]";
            if (!(value instanceof JSONObject node)) {
                throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR,
                        at + " 需为对象 {name,type,mapping|children}，当前是 "
                                + (value == null ? "null" : value.getClass().getSimpleName()));
            }
            checkNode(node, at, consumed, names);
        }
    }

    private static void checkNode(JSONObject node, String at, boolean consumed, Set<String> names) {
        String name = node.getString("name");
        if (consumed) {
            if (StringUtils.isBlank(name)) {
                throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR,
                        at + " 缺少 name（bodyFields 的每个节点都要有输出字段名）");
            }
            if (!names.add(name)) {
                throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR,
                        at + " 与同级节点重名: " + name
                                + "。运行期按顺序 put，后者覆盖前者，前一个节点是死配置");
            }
        }
        at = at + (StringUtils.isBlank(name) ? "" : "(" + name + ")");
        String type = nodeType(node, at);
        switch (type) {
            case "object" -> {
                if (consumed && node.get("mapping") != null) {
                    throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR,
                            at + " 是 object，不能写 mapping。object 只认 children，"
                                    + "运行期会忽略这个 mapping（不报错），于是你以为透传了对象，"
                                    + "实际发出去的是 children 拼出来的内容或空对象 {}。"
                                    + "要整体透传一个对象入参请改用 bodyRaw");
                }
                JSONArray children = node.getJSONArray("children");
                if (children != null) {
                    checkNodes(children, at + ".children", consumed);
                }
            }
            case "array" -> {
                if (consumed && StringUtils.isBlank(node.getString("mapping"))) {
                    throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR,
                            at + " 是 array，必须写 mapping（指向提供整个数组的输入参数名）。"
                                    + "运行期取不到 mapping 即报 EXEC_INPUT_INVALID");
                }
                Object item = node.get("item");
                if (item == null) {
                    return;
                }
                if (!(item instanceof JSONObject itemObj)) {
                    throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR,
                            at + ".item 需为对象 {type,children}，当前是 " + item.getClass().getSimpleName());
                }
                // item 仅结构声明，运行期不消费：只查形状与 type，不要求 name/mapping。
                // 这里传 consumed=false 是关键——把消费模式的规则（叶子必须有 mapping）
                // 套进 item 子树，会把「只声明元素长什么样」的正确写法全部误拒。
                checkNode(itemObj, at + ".item", false, new HashSet<>());
            }
            default -> {
                if (consumed && StringUtils.isBlank(node.getString("mapping"))) {
                    throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR,
                            at + " 是叶子节点（" + type + "），必须写 mapping（输入参数名，"
                                    + "可选参数加 opt: 前缀，字面量加 literal: 前缀）");
                }
            }
        }
    }

    private static String nodeType(JSONObject node, String at) {
        String type = StringUtils.defaultIfBlank(node.getString("type"), "string")
                .toLowerCase(Locale.ROOT);
        if (NODE_TYPES.contains(type)) {
            return type;
        }
        String alias = TYPE_ALIASES.get(type);
        throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR,
                at + " 的 type 不合法: " + type + "。只支持 string/number/boolean/object/array"
                        + (alias == null ? ""
                        : "；JSON Schema 的 " + type + " 在这里要写成 " + alias
                        + "（number 运行期按有没有小数点自动转 Long/Double，不需要再分 integer）")
                        + "。运行期遇到不认识的 type 会直接报 EXEC_INPUT_INVALID");
    }

    // ---------- query / headers ----------

    /**
     * query / headers 是 {参数名: 映射}。值必须是非空字符串：运行期走 {@code getString}
     * 把它当<b>输入参数名</b>用，数字/布尔/对象会被 String.valueOf 成 "100"/"true"/"{...}"
     * 再去找同名入参，必然缺参；空串同理。
     * <p>
     * null 分支保留但<b>当前不可达</b>：{@code httpSpec} 在 DTO 反序列化那一层就把 null 值丢掉了
     * （实测 {@code {"query":{"page":null}}} 落到库里是 {@code "query": {}}，等价于不传该参数），
     * 直接读 bundle 的导入路径也一样。留着是为了传输层将来换成保留 null 的实现时不至于漏掉——
     * 那时它会退化成「原样发出字符串 "null"」，是错值而非空值，属于最难排查的一类。
     */
    private static void checkMappingMap(JSONObject spec, String key, String where) {
        Object value = spec.get(key);
        if (value == null) {
            return;
        }
        if (!(value instanceof JSONObject map)) {
            throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR,
                    where + "." + key + " 需为对象 {参数名: 输入参数名}，当前是 "
                            + value.getClass().getSimpleName());
        }
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object mapping = entry.getValue();
            if (mapping instanceof String s && StringUtils.isNotBlank(s)) {
                continue;
            }
            throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR,
                    where + "." + key + "." + entry.getKey() + " 的值必须是非空字符串（输入参数名，"
                            + "可加 opt:/literal: 前缀），当前是 "
                            + (mapping == null ? "null" : mapping.getClass().getSimpleName() + "(" + mapping + ")")
                            + "。这个值会被当成输入参数名去找入参，"
                            + (mapping == null ? "null" : "非字符串")
                            + " 找不到对应入参，执行时必报缺参");
        }
    }

    private static void checkResponse(JSONObject spec, String where) {
        Object value = spec.get("response");
        if (value == null) {
            return;
        }
        if (!(value instanceof JSONObject response)) {
            throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR,
                    where + ".response 需为对象 {extract: \"$.a.b\"}，当前是 "
                            + value.getClass().getSimpleName());
        }
        Object extract = response.get("extract");
        if (extract != null && !(extract instanceof String)) {
            throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR,
                    where + ".response.extract 必须是字符串（如 \"$.data.items\"），当前是 "
                            + extract.getClass().getSimpleName());
        }
    }
}
