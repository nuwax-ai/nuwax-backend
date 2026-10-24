package com.xspaceagi.userapp.application.oauth2;

import java.util.List;

import com.xspaceagi.userapp.domain.model.UserProjectModel;

/**
 * 保存接入设置的结果。
 *
 * @param project                     保存后的应用配置行（就地更新过，字段为最新值）
 * @param skippedRedirectUriRemovals  本次请求里「要删但没删成」的补充回调地址。
 *                                    沙箱（AI）来源只能删自己加的条目，删到人工条目会被跳过；
 *                                    其余情况恒为空列表。
 *                                    <p>
 *                                    ★刻意用「跳过 + 回报」而不是抛错：一次请求可能同时要删若干条，
 *                                    其中一部分没权限。抛错会让调用方以为整次失败而原样重试（永远失败），
 *                                    也会把已经生效的那部分改动掩盖掉。部分成功 + 显式回报才可操作。
 */
public record ThirdAppOAuthSettingResult(UserProjectModel project, List<String> skippedRedirectUriRemovals) {

    /** 没有被跳过的删除项 */
    public static ThirdAppOAuthSettingResult of(UserProjectModel project) {
        return new ThirdAppOAuthSettingResult(project, List.of());
    }
}
