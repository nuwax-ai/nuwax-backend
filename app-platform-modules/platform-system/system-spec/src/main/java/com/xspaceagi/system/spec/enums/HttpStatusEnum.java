package com.xspaceagi.system.spec.enums;

public enum HttpStatusEnum {

    CONTINUE(100, "Continue", "请继续发送请求的剩余部分"),
    SWITCHING_PROTOCOLS(101, "Switching Protocols", "协议切换"),
    PROCESSING(102, "Processing", "请求将继续执行"),
    // for 103 https://news.ycombinator.com/item?id=15590049
    CHECKPOINT(103, "Checkpoint", "可以预加载"),
    OK(200, "OK", "请求已经成功处理"),
    CREATED(201, "Created", "请求已经成功处理，并创建了资源"),
    ACCEPTED(202, "Accepted", "请求已经接受，等待执行"),
    NON_AUTHORITATIVE_INFORMATION(203, "Non-Authoritative Information", "请求已经成功处理，但是信息不是原始的"),
    NO_CONTENT(204, "No Content", "请求已经成功处理，没有内容需要返回"),
    RESET_CONTENT(205, "Reset Content", "请求已经成功处理，请重置视图"),
    PARTIAL_CONTENT(206, "Partial Content", "部分Get请求已经成功处理"),
    MULTI_STATUS(207, "Multi-Status", "请求已经成功处理，将返回XML消息体"),
    ALREADY_REPORTED(208, "Already Reported", "请求已经成功处理，一个DAV的绑定成员被前一个请求枚举，并且没有被再一次包括"),
    IM_USED(226, "IM Used", "请求已经成功处理，将响应一个或者多个实例"),
    MULTIPLE_CHOICES(300, "Multiple Choices", "提供可供选择的回馈"),
    MOVED_PERMANENTLY(301, "Moved Permanently", "请求的资源已经永久转移"),
    FOUND(302, "Found", "请重新发送请求"),
    // MOVED_TEMPORARILY(302, "Moved Temporarily", "") 已经过时
    SEE_OTHER(303, "See Other", "请以Get方式请求另一个URI"),
    NOT_MODIFIED(304, "Not Modified", "资源未改变"),
    USE_PROXY(305, "Use Proxy", "请通过Location域中的代理进行访问"),
    // 306在新版本的规范中被弃用
    TEMPORARY_REDIRECT(307, "Temporary Redirect", "请求的资源临时从不同的URI响应请求"),
    RESUME_INCOMPLETE(308, "Resume Incomplete", "请求的资源已经永久转移"),
    BAD_REQUEST(400, "Bad Request", "请求错误，请修正请求"),
    UNAUTHORIZED(401, "Unauthorized", "没有被授权或者授权已经失效"),
    PAYMENT_REQUIRED(402, "Payment Required", "预留状态"),
    FORBIDDEN(403, "Forbidden", "请求被理解，但是拒绝执行"),
    NOT_FOUND(404, "Not Found", "资源未找到"),
    METHOD_NOT_ALLOWED(405, "Method Not Allowed", "请求方法不允许被执行"),
    NOT_ACCEPTABLE(406, "Not Acceptable", "请求的资源不满足请求者要求"),
    PROXY_AUTHENTICATION_REQUIRED(407, "Proxy Authentication Required", "请通过代理进行身份验证"),
    REQUEST_TIMEOUT(408, "Request Timeout", "请求超时"),
    CONFLICT(409, "Conflict", "请求冲突"),
    GONE(410, "Gone", "请求的资源不可用"),
    LENGTH_REQUIRED(411, "Length Required", "Content-Length未定义"),
    PRECONDITION_FAILED(412, "Precondition Failed", "不满足请求的先决条件"),
    REQUEST_ENTITY_TOO_LARGE(413, "Request Entity Too Large", "请求发送的实体太大"),
    REQUEST_URI_TOO_LONG(414, "Request-URI Too Long", "请求的URI超长"),
    UNSUPPORTED_MEDIA_TYPE(415, "Unsupported Media Type", "请求发送的实体类型不受支持"),
    REQUESTED_RANGE_NOT_SATISFIABLE(416, "Requested range not satisfiable", "Range指定的范围与当前资源可用范围不一致"),
    EXPECTATION_FAILED(417, "Expectation Failed", "请求头Expect中指定的预期内容无法被服务器满足"),
    // I_AM_A_TEAPOT(418, "I'm a teapot", ""), 该代码没有被服务器实现

    // INSUFFICIENT_SPACE_ON_RESOURCE(419, "Insufficient Space On Resource", ""),  已经过时

    // METHOD_FAILURE(420, "Method Failure", ""),  已经过时

    // DESTINATION_LOCKED(421, "Destination Locked", ""),  已经过时
    UNPROCESSABLE_ENTITY(422, "Unprocessable Entity", "请求格式正确，但是由于含有语义错误，无法响应"),
    LOCKED(423, "Locked", "当前资源被锁定"),
    FAILED_DEPENDENCY(424, "Failed Dependency", "由于之前的请求发生错误，导致当前请求失败"),
    UPGRADE_REQUIRED(426, "Upgrade Required", "客户端需要切换到TLS1.0"),
    PRECONDITION_REQUIRED(428, "Precondition Required", "请求需要提供前置条件"),
    TOO_MANY_REQUESTS(429, "Too Many Requests", "请求过多"),
    REQUEST_HEADER_FIELDS_TOO_LARGE(431, "Request Header Fields Too Large", "请求头超大，拒绝请求"),
    INTERNAL_SERVER_ERROR(500, "Internal Server Error", "服务器内部错误"),
    NOT_IMPLEMENTED(501, "Not Implemented", "服务器不支持当前请求的部分功能"),
    BAD_GATEWAY(502, "Bad Gateway", "响应无效"),
    SERVICE_UNAVAILABLE(503, "Service Unavailable", "服务器维护或者过载，拒绝服务"),
    GATEWAY_TIMEOUT(504, "Gateway Timeout", "上游服务器超时"),
    HTTP_VERSION_NOT_SUPPORTED(505, "HTTP Version not supported", "不支持的HTTP版本"),
    VARIANT_ALSO_NEGOTIATES(506, "Variant Also Negotiates", "服务器内部配置错误"),
    INSUFFICIENT_STORAGE(507, "Insufficient Storage", "服务器无法完成存储请求所需的内容"),
    LOOP_DETECTED(508, "Loop Detected", "服务器处理请求时发现死循环"),
    BANDWIDTH_LIMIT_EXCEEDED(509, "Bandwidth Limit Exceeded", "服务器达到带宽限制"),
    NOT_EXTENDED(510, "Not Extended", "获取资源所需的策略没有被满足"),
    NETWORK_AUTHENTICATION_REQUIRED(511, "Network Authentication Required", "需要进行网络授权");

    private final int code;
    private final String reasonPhraseUS;
    private final String reasonPhraseCN;

    private static final int
            INFORMATIONAL = 1,
            successFUL = 2,
            REDIRECTION = 3,
            CLIENT_ERROR = 4,
            SERVER_ERROR = 5;

    HttpStatusEnum(int code, String reasonPhraseUS, String reasonPhraseCN) {
        this.code = code;
        this.reasonPhraseUS = reasonPhraseUS;
        this.reasonPhraseCN = reasonPhraseCN;
    }

    public int code() {
        return code;
    }

    public String reasonPhraseUS() {
        return reasonPhraseUS;
    }

    public String reasonPhraseCN() {
        return reasonPhraseCN;
    }

    public static HttpStatusEnum valueOf(int code) {
        for (HttpStatusEnum httpStatus : values()) {
            if (httpStatus.code() == code) {
                return httpStatus;
            }
        }
        throw new IllegalArgumentException("No matching constant for [" + code + "]");
    }

    public boolean is1xxInformational() {
        return type() == INFORMATIONAL;
    }

    public boolean is2xxSuccessful() {
        return type() == successFUL;
    }

    public boolean is3xxRedirection() {
        return type() == REDIRECTION;
    }

    public boolean is4xxClientError() {
        return type() == CLIENT_ERROR;
    }

    public boolean is5xxServerError() {
        return type() == SERVER_ERROR;
    }

    private int type() {
        return (int) code / 100;
    }
}