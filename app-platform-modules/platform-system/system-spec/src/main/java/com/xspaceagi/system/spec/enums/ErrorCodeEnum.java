package com.xspaceagi.system.spec.enums;

public enum ErrorCodeEnum {

    SUCCESS("0000", "请求成功"),
    ERROR_REQUEST("0001", "请求失败"),
    INVALID_PARAM("4000", "参数错误"),

    INVALID_INVITE_CODE("4001", "邀请码错误"),

    PERMISSION_DENIED("4030", "无权限"),
    RESOURCE_PERMISSION_DENIED("4033", "无此资源权限"),
    UNAUTHORIZED("4010", "未登录或登录超时"),
    UNAUTHORIZED_REDIRECT("4011", "未登录或登录超时"),
    API_NOT_FOUND("4040", "接口不存在"),
    METHOD_NOT_ALLOWED("4050", "未登录或登录超时"),
    SYS_ERROR("5000", "系统异常"),

    PROJECT_STARTING("6001", "项目启动中,请稍等"),

    /** License 文件内容解析失败（与历史契约码 0400 对齐） */
    LICENSE_CONTENT_INVALID("0400", "许可证内容无效");

    private String code;
    private String msg;

    /**
     * @param code
     * @param msg
     */
    ErrorCodeEnum(String code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    /**
     * @return the code
     */
    public String getCode() {
        return code;
    }

    /**
     * @param code the code to set
     */

    /**
     * @return the msg
     */
    public String getMsg() {
        return msg;
    }

}