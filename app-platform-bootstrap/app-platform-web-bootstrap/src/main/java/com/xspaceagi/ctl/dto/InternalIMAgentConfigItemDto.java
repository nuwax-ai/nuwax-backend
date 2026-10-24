package com.xspaceagi.ctl.dto;

import java.io.Serializable;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 内部调用接口「消息会话默认智能体配置」条目（租户配置项 {@code defaultIMAgentConfig} 的一项）。
 * <p>
 * 配置值是一个 JSON 数组，形如 {@code [{"agentId": 123, "defaultMessage": ""}]}，
 * 接口按这个结构解析后逐项下发（{@code data} 即该数组）。<b>这是与调用方约定的结构</b>：
 * 调用方按 {@code agentId} 找默认智能体、按 {@code defaultMessage} 拿开场白。
 * <p>
 * 只回这两个字段、不整包透传原值：调用方拿到的是可直接使用的结构，不必各自再解一遍 JSON，
 * 也就不会出现「同一个配置在不同调用方那里被解释成不同结果」。
 */
@Data
public class InternalIMAgentConfigItemDto implements Serializable {

    @Schema(description = "默认智能体ID；缺失该字段的配置项会被丢弃（没有 agentId 的条目无法落地）")
    private Long agentId;

    @Schema(description = "默认消息（开场白）；未配置时为空串，不下发 null")
    private String defaultMessage;
}
