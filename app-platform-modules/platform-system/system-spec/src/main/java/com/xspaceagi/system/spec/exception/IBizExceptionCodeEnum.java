package com.xspaceagi.system.spec.exception;

import com.xspaceagi.system.spec.common.RequestContext;

public interface IBizExceptionCodeEnum {

    String getCode();

    /**
     * 中文错误信息模板（与 {@link String#format} 占位一致）
     */
    String getMessageZh();

    /**
     * 英文错误信息模板
     */
    String getMessageEn();

    /**
     * 分类备注,方便看异常定义,不参与错误提示
     */
    String getRemark();

    /**
     * 按当前请求 {@link RequestContext#getLang()} 选择模板：仅 {@code zh-CN}（忽略大小写）使用中文，其余（含未设置 lang）使用英文。
     */
    default String pickMessageTemplateForRequest() {
        RequestContext<?> ctx = RequestContext.get();
        if (ctx != null) {
            String lang = ctx.getLang();
            if (lang != null && !lang.isBlank() && "zh-CN".equalsIgnoreCase(lang.trim())) {
                return getMessageZh();
            }
        }
        return getMessageEn();
    }

    /**
     * 使用 {@link #pickMessageTemplateForRequest()} 的模板做 {@link String#format}。
     */
    default String formatMessage(Object... params) {
        String tpl = pickMessageTemplateForRequest();
        if (params == null || params.length == 0) {
            return tpl;
        }
        return String.format(tpl, params);
    }
}
