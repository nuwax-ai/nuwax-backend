package com.xspaceagi.repo.web.dto;

import lombok.Data;

import java.util.List;

/**
 * 整体覆盖文档级按人权限请求（需求7）
 */
@Data
public class RepoPageAclPutRequest {

    /**
     * 按人权限开关：0=关闭 1=开启（开启后未配置成员降为仅阅读）
     */
    private Integer aclEnabled;

    /**
     * 权限条目（整体覆盖）
     */
    private List<Entry> entries;

    @Data
    public static class Entry {

        /**
         * 用户ID
         */
        private Long userId;

        /**
         * 权限位组合：1查看|2编辑|4分享|8导出，查看恒含
         */
        private Integer permBits;
    }
}
