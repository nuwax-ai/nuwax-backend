package com.xspaceagi.userapp.sdk.dto;

import java.io.Serializable;
import java.util.Date;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一条 OAuth2 补充回调地址及其归属。以对象数组存入 user_project.redirect_uris（JSON 列）。
 * <p>
 * 为什么要记归属：补充地址是「谁都能加」的共享列表——平台网页上的人工配置与沙箱里的 AI
 * 会写同一行。没有来源标记时，任何一方提交都会把对方的条目一并抹掉（整表替换语义），
 * 而回调地址是授权码流程的安全边界，被静默改写等于把用户的登录打断。
 * <p>
 * 因此 {@link #source} 是服务端执行删除权限的<b>唯一判据</b>：{@link Source#Sandbox}
 * 只能删自己加的条目，删到 {@link Source#Manual} 的条目一律跳过（并在响应里回报，不抛错——
 * 抛错会让调用方以为整次失败而重试）。人工/前端不受限，应用属主对自己的应用全权。
 * {@link #createdBy} 与 {@link #createdAt} 不参与权限判定，只供审计与前端展示。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RedirectUriEntry implements Serializable {

    /**
     * 条目由谁写入。
     * <ul>
     *   <li>{@link #Manual} —— 平台网页/前端的人工配置，属主全权，任何来源都能删它</li>
     *   <li>{@link #Sandbox} —— 沙箱里的 AI 通过 /api/v1/4sandbox/** 写入，只能删同来源的条目</li>
     * </ul>
     * 判据取自 {@code SandboxUtils.isSandboxRequest(request)}，由 web 层判定后往下传，
     * 业务层不感知传输方式。
     */
    public enum Source {
        Manual,
        Sandbox
    }

    /** 回调地址本身。精确匹配：不做通配、前缀与任何 URL 规范化 */
    private String uri;

    /** 写入来源，决定删除权限 */
    private Source source;

    /** 写入人 userId（审计与前端展示用，不参与权限判定） */
    private Long createdBy;

    /** 写入时间（审计与前端展示用） */
    private Date createdAt;
}
