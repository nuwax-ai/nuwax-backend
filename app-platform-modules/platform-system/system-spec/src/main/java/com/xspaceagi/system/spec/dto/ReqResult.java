package com.xspaceagi.system.spec.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Schema(description = "请求响应信息")
public class ReqResult<T> implements Serializable {

    public static final String SUCCESS = "0000";

    @Schema(description = "业务状态码，0000 表示成功，其余失败", example = "0000")
    private String code = SUCCESS;

    @Schema(description = "源系统状态码，用于问题跟踪", example = "0000")
    private String displayCode;

    @Schema(description = "错误描述信息")
    private String message;

    @Schema(description = "返回的具体业务数据")
    private T data = null;

    @Schema(description = "跟踪唯一标识")
    private String tid;


    public ReqResult(final String code, final String displayCode, final String message) {
        this.code = code;
        this.displayCode = displayCode;
        this.message = message;
    }

    public ReqResult(String code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }


    public ReqResult(String code, String displayCode, String message, T data) {
        this.code = code;
        this.displayCode = displayCode;
        this.message = message;
        this.data = data;
    }


    public boolean isSuccess() {
        return "0000".equals(this.code);
    }

    public static <T> ReqResult<T> success() {
        return create("0000", "成功", null);
    }

    public static <T> ReqResult<T> success(T obj) {
        return create("0000", "success", obj);
    }

    public static <Void> ReqResult<Void> error(String msg) {
        return create("0001", msg, null);
    }

    public static <Void> ReqResult<Void> error(String code, String displayCode, String msg) {
        return create(code, displayCode, msg, null);
    }

    public static <Void> ReqResult<Void> error(String code, String msg) {
        return create(code, code, msg, null);
    }

    public static <T> ReqResult<T> create(String code, String msg, T data) {

        return new ReqResult(code, code, msg, data);
    }


    public static <T> ReqResult<T> create(String displayCode, String code, String msg, T data) {
        return new ReqResult(code, displayCode, msg, data);
    }


}
