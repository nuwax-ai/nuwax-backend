package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Collections;
import java.util.List;

/**
 * ★成员列表（契约 {@code MemberPageRpcDto}）。
 *
 * <h2>★★{@code degraded=true} 是<b>降级</b>，不是"就这么多成员"</h2>
 *
 * <p>群成员 &gt;500 时服务端只返回<b>前 100</b> 并把 {@code degraded} 置 true（02 §12.2.2）。
 * 调用方若只看 {@code members.size()} 就会把"前 100 个"当成"全部成员"，
 * 于是出现"群成员列表少了一大半"的线上问题。
 * ⇒ 展示层必须依据 {@code degraded} 给出"仅显示前 100 位成员"之类的提示，
 * 需要完整名单时走客户端长连接侧的成员接口（那是另一条路径，不受此降级约束）。
 *
 * @param totalCount 成员总数（★降级时它仍是<b>真实总数</b>，不是 members.size()）
 * @param degraded   true = 本次结果被截断为前 100 条
 * @param members    成员条目（≤100；服务端返 null 时由 {@link #membersOrEmpty()} 兜底）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MemberPageDto(Integer totalCount,
                            Boolean degraded,
                            List<MemberItemDto> members) {

    /** ★空安全列表。 */
    public List<MemberItemDto> membersOrEmpty() {
        return members == null ? Collections.emptyList() : members;
    }

    /** ★空安全：未返回视为 true —— <b>保守取向</b>：把"不知道是否降级"当成降级，
     *  宁可多提示一句，也不要把被截断的名单当成完整名单展示。 */
    public boolean degradedOrTrue() {
        return degraded == null || degraded;
    }

    /** ★本次返回的条数（不是总数）。 */
    public int size() {
        return membersOrEmpty().size();
    }

    /** ★是否可能还有未返回的成员（降级且总数大于本次条数）。 */
    public boolean truncated() {
        return degradedOrTrue() && totalCount != null && totalCount > size();
    }
}
