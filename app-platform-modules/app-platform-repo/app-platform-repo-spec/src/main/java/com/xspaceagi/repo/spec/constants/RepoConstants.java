package com.xspaceagi.repo.spec.constants;

/**
 * 文档协作模块通用常量。
 */
public final class RepoConstants {

    private RepoConstants() {
    }

    /**
     * 软删除标记：有效（平台惯例，对齐 space 表 yn）
     */
    public static final int YN_VALID = 1;

    /**
     * 软删除标记：已删除
     */
    public static final int YN_DELETED = 0;

    /**
     * 评论已解决
     */
    public static final int COMMENT_RESOLVED = 1;

    /**
     * 评论未解决
     */
    public static final int COMMENT_UNRESOLVED = 0;

    /**
     * 页面短链ID长度（slug_id）
     */
    public static final int SLUG_ID_LENGTH = 16;

    /**
     * fractional index 默认排序值（新根节点初始值，后续节点取 a0/a1 中间串）
     */
    public static final String DEFAULT_POSITION = "a0";

    /**
     * 空间角色：属主（对齐 SpaceUser.Role.Owner.name()）
     */
    public static final String ROLE_OWNER = "Owner";

    /**
     * 空间角色：管理员（对齐 SpaceUser.Role.Admin.name()）
     */
    public static final String ROLE_ADMIN = "Admin";

    /**
     * 空间角色：普通成员（对齐 SpaceUser.Role.User.name()）
     */
    public static final String ROLE_USER = "User";

    /**
     * 版本来源：Sidecar 定时自动保存
     */
    public static final String VERSION_REASON_AUTOSAVE = "autosave";

    /**
     * 版本来源：用户手动保存
     */
    public static final String VERSION_REASON_MANUAL = "manual";

    /**
     * 版本来源：恢复历史版本产生
     */
    public static final String VERSION_REASON_RESTORE = "restore";

    // ============ 权限位（需求4分享/需求7按人权限共用，位运算组合） ============

    /**
     * 权限位：可查看
     */
    public static final int PERM_VIEW = 1;

    /**
     * 权限位：可编辑
     */
    public static final int PERM_EDIT = 2;

    /**
     * 权限位：可分享
     */
    public static final int PERM_SHARE = 4;

    /**
     * 权限位：可导出
     */
    public static final int PERM_EXPORT = 8;

    /**
     * 权限位：可评论（需求5：分享评论权限；空间分享走位，链接分享走 allow_comment 列）
     */
    public static final int PERM_COMMENT = 16;

    /**
     * 权限位：可复制（创建副本；空间分享/按人权限走位，链接分享不可复制）
     */
    public static final int PERM_COPY = 32;

    /**
     * 全权限位组合（源空间普通成员未开启按人权限时的默认权限）
     */
    public static final int PERM_FULL = PERM_VIEW | PERM_EDIT | PERM_SHARE | PERM_EXPORT | PERM_COMMENT | PERM_COPY;

    /**
     * 分享链接访问方式：登录可访问
     */
    public static final String SHARE_LINK_LOGIN = "login";

    /**
     * 分享链接访问方式：任何人可访问（含匿名）
     */
    public static final String SHARE_LINK_PUBLIC = "public";

    /**
     * 分享链接 token 长度
     */
    public static final int SHARE_LINK_TOKEN_LENGTH = 24;

    /**
     * editor_ids 最多记录的编辑者数量（超出裁掉最早的）
     */
    public static final int EDITOR_IDS_MAX = 200;
}
