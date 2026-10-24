package com.xspaceagi.knowledge.domain.docparser;

import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.KnowledgeException;

/**
 * 知识库文档处理依赖的对话模型不可用: 模型已删除/配置悬空,或租户未配置默认对话模型。
 * 属于永久性业务配置错误: 不重试、不回退全局默认模型(该模型不向租户开放),
 * 直接失败并把原因写入 knowledge_task.result 向用户展示,提示先修复模型配置。
 * <p>
 * 异常同时保留错误码与原始参数({@link #encodedResult()}),供持久化结构化结果——
 * 后台线程格式化的消息语言不可控(定时重试路径无请求上下文),读取侧需按查看者语言重渲染。
 */
public class SmartParsingModelUnavailableException extends KnowledgeException {

    private final BizExceptionCodeEnum codeEnum;
    private final Object[] formatParams;

    public SmartParsingModelUnavailableException() {
        this(BizExceptionCodeEnum.agentTenantDefaultModelMissing);
    }

    public SmartParsingModelUnavailableException(BizExceptionCodeEnum codeEnum, Object... params) {
        super(codeEnum.getCode(), codeEnum.formatMessage(params));
        this.codeEnum = codeEnum;
        this.formatParams = params;
    }

    /** 结构化编码(如 "9391"、"9390:281"),持久化到 knowledge_task.result,读取时按语言重渲染 */
    public String encodedResult() {
        return codeEnum.encode(formatParams);
    }
}
