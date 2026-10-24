package com.xspaceagi.userapp.sdk.dto;

import java.util.List;
import java.util.Map;

import lombok.Builder;
import lombok.Data;

/**
 * 下游 userapp 容器接口调用结果
 */
@Data
@Builder
public class UserAppCallResult {

    public static final String SUCCESS_CODE = "0000";

    /** 下游"应用不存在"错误码（含 HTTP 404） */
    public static final String ERR_APP_NOT_FOUND = "ERR_APP_NOT_FOUND";

    private String code;

    private String message;

    private Map<String, Object> data;

    /** 下游 data 为数组的场景（如日志源查询），data 为 Map 时为 null */
    private List<Map<String, Object>> dataList;

    public boolean isSuccess() {
        return SUCCESS_CODE.equals(code);
    }

    public String dataString(String key) {
        if (data == null || key == null) {
            return null;
        }
        Object value = data.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** 受理响应的下游任务ID */
    public String getDownstreamTaskId() {
        return dataString("task_id");
    }

    /** 任务详情的发布版本ID（build 任务完成后用于记录发布版本） */
    public String getDownstreamReleaseId() {
        return dataString("release_id");
    }

    /** start/restart 受理响应的任务状态 */
    public String getDownstreamStatus() {
        return dataString("status");
    }

    /** 任务详情的失败原因 */
    public String getDownstreamError() {
        return dataString("error");
    }
}
