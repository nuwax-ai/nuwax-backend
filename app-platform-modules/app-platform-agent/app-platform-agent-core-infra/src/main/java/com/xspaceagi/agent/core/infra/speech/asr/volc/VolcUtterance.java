package com.xspaceagi.agent.core.infra.speech.asr.volc;

/** 火山实时 ASR 分句；definite=true 表示可作定稿句。 */
public record VolcUtterance(
        String text,
        Integer beginMs,
        Integer endMs,
        boolean definite) {
}
