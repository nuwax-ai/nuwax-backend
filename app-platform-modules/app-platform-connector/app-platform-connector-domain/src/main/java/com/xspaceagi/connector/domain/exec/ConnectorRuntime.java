package com.xspaceagi.connector.domain.exec;

import com.xspaceagi.connector.domain.dto.ExecResultDto;

/**
 * CUSTOM 动作运行时接口（路径 B，设计文档 §4.3）。
 * <p>
 * 仅官方层（systemLevel）可为 action 指定 execType=CUSTOM；实现以 Spring Bean 注册进
 * {@code ConnectorRuntimeRegistry}，按 exec_ref 查找。未注册的 exec_ref 一律
 * EXEC_CUSTOM_NOT_REGISTERED。
 * <p>
 * 内置实现见 {@code com.xspaceagi.connector.application.exec.runtime}（目前一个：
 * {@code AuthConfigRuntime}，对应凭证发放工具 get_auth_config）。
 */
public interface ConnectorRuntime {

    /**
     * 与 connector_action.exec_ref 对应的唯一标识。
     */
    String execRef();

    /**
     * 执行 CUSTOM 动作。实现方不得把 credentialJson 传出模块边界或写入日志。
     * <p>
     * 例外：以「发放凭证」为职责的运行时（get_auth_config）需要把凭证放进 {@code data}，
     * 这属于有意放宽，须在实现类 javadoc 写明决定来源，并给结果打上
     * {@code ConnectorConstants.META_SENSITIVE_PAYLOAD} 标记，使审计侧整体省略 outputPreview。
     */
    ExecResultDto execute(RuntimeContext context);
}
