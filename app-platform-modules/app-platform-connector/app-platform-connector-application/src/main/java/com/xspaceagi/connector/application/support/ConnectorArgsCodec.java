package com.xspaceagi.connector.application.support;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.connector.spec.enums.DataTypeEnum;
import com.xspaceagi.connector.spec.error.ConnectorErrorCode;
import com.xspaceagi.connector.spec.exception.ConnectorException;
import com.xspaceagi.connector.spec.model.Arg;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * inputArgs 编解码：统一为类型化 Arg 树结构
 * {@code [{name, description, dataType, require, enable, subArgs}]}。
 * <p>
 * 旧版扁平 map（{参数名: 说明}）无法区分参数类型，已废弃：传入扁平对象会报
 * VALIDATION_ERROR 并提示新结构。dataType 缺省按 String 处理（与平台插件侧一致）；
 * enable 为 null 视为开启。绑定即校验：非法 dataType 枚举名、缺 name 等直接拒绝。
 */
public final class ConnectorArgsCodec {

    private ConnectorArgsCodec() {
    }

    /**
     * 校验并归一为 Arg 树 JSON 数组（去掉未知字段）。raw 为 null 时返回 null。
     *
     * @param raw   JSONArray / List / JSON 字符串（必须能解析为 Arg 数组）
     * @param where 报错位置描述，如 "provider github 的 actions[3].inputArgs"
     */
    public static JSONArray normalizeToJson(Object raw, String where) {
        if (raw == null) {
            return null;
        }
        Object parsed;
        try {
            // Spring 侧反序列化后嵌套数组是 ArrayList（Jackson），非 fastjson2 JSONArray：
            // 统一 JSON.toJSON 归一，保证 instanceof 判定正确（旧扁平 map 仍被拒）
            parsed = raw instanceof String s ? JSON.parse(s) : JSON.toJSON(raw);
        } catch (Exception e) {
            throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR,
                    where + " 不是合法 JSON: " + e.getMessage());
        }
        if (parsed instanceof JSONObject) {
            throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR,
                    where + " 需为 Arg 数组结构 [{name,description,dataType,require,enable,subArgs}]，"
                            + "不再接受 {参数名:说明} 扁平 map");
        }
        if (!(parsed instanceof JSONArray arr)) {
            throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR,
                    where + " 需为 Arg 数组结构");
        }
        List<Arg> args;
        try {
            args = arr.toJavaList(Arg.class);
        } catch (Exception e) {
            throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR,
                    where + " Arg 绑定失败（检查 dataType 枚举值/字段类型）: " + e.getMessage());
        }
        validateTree(args, where);
        return (JSONArray) JSON.toJSON(args);
    }

    /**
     * 校验并归一为 JSON 字符串（落库形态）。raw 为 null 时返回 null。
     */
    public static String normalize(Object raw, String where) {
        JSONArray normalized = normalizeToJson(raw, where);
        return normalized == null ? null : normalized.toJSONString();
    }

    private static void validateTree(List<Arg> args, String where) {
        if (args == null) {
            return;
        }
        for (Arg arg : args) {
            if (arg == null || StringUtils.isBlank(arg.getName())) {
                throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR,
                        where + " 存在缺少 name 的参数");
            }
            if (arg.getDataType() == null) {
                arg.setDataType(DataTypeEnum.String);
            }
            validateTree(arg.getSubArgs(), where + "." + arg.getName() + ".subArgs");
        }
    }
}
