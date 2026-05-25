package com.xspaceagi.pay.sdk.dto;

import com.xspaceagi.pay.sdk.OpenApiCodes;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ApiResponse<T> {
    private String code;
    private String message;
    private T data;

    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder().code(OpenApiCodes.SUCCESS).message("Success").data(data).build();
    }

    public static <T> ApiResponse<T> fail(String code, String message) {
        return fail(code, message, null);
    }

    /** 业务失败但附带部分 data（如 bootstrap 会话无效时仍返回收银台品牌字段） */
    public static <T> ApiResponse<T> fail(String code, String message, T data) {
        return ApiResponse.<T>builder().code(code).message(message).data(data).build();
    }
}
