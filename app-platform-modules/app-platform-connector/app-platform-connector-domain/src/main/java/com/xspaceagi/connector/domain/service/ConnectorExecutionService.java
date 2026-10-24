package com.xspaceagi.connector.domain.service;

import com.xspaceagi.connector.domain.dto.ExecResultDto;

import java.util.Map;

/**
 * 连接器执行服务（路径 A 声明式 / B CUSTOM / C 通用代理，设计文档 §4）。
 */
public interface ConnectorExecutionService {

    /**
     * 执行动作。
     *
     * @param actionId        动作 id（与 service+actionKey 二选一）
     * @param providerService provider service（actionId 为空时必填）
     * @param actionKey       动作 key（actionId 为空时必填）
     * @param connectionId    指定连接；为空则按 (tenant,user,service) 自动取第一个 active 连接
     * @param args            输入参数
     * @param agentId         来源 agent，可空
     */
    ExecResultDto execute(Long actionId, String providerService, String actionKey, Long connectionId,
                          Map<String, Object> args, Long agentId);

    /**
     * 连接验证：挑 whoami 类动作执行并回填 profile（尽力而为）。
     *
     * @return success=true 表示连接可用；meta.profileUpdated 表示是否回填了 profile
     */
    ExecResultDto verifyConnection(Long connectionId);

    /**
     * 通用代理（路径 C）：provider.proxy_enabled=1 才可用；凭证模块内注入，
     * 客户端自带鉴权头一律不转发。
     *
     * @param method       GET/POST/PUT/PATCH/DELETE
     * @param relativePath 相对 base_url 的路径（必须 / 开头，禁 ../）
     * @param query        查询参数
     * @param body         请求体，可空
     * @param connectionId 指定连接，可空（自动取第一个 active）
     */
    ExecResultDto proxy(String providerService, String method, String relativePath,
                        Map<String, String> query, String body, Long connectionId);
}
