package com.xspaceagi.system.application.service;

import java.util.Map;

/**
 * 权限配置差异比对服务
 */
public interface PermissionDiffService {

    /**
     * 比对两个版本的权限 JSON，生成差异结构（fromVersion -> toVersion）
     *
     * @param fromVersion 旧版本号，对应 permission-{fromVersion}.json
     * @param toVersion   新版本号，对应 permission-{toVersion}.json
     * @return 差异 JSON 结构，含 fromVersion、toVersion 及各实体的 added/removed/modified
     */
    Map<String, Object> generateDiff(String fromVersion, String toVersion);
}
