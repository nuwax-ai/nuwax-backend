-- ================================================================
-- 文档协作（repo）模块建表脚本
-- 表前缀 repo_*，遵守平台惯例：AUTO 自增主键、_tenant_id 租户列、
-- created/modified 时间列、yn 软删除标记（1 有效 / 0 删除）
-- ================================================================

-- ========== 文档页面（核心表） ==========
CREATE TABLE IF NOT EXISTS `repo_page` (
    `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `_tenant_id`       BIGINT NOT NULL COMMENT '租户ID',
    `space_id`         BIGINT NOT NULL COMMENT '所属空间（平台 space 表）',
    `parent_id`        BIGINT UNSIGNED NULL COMMENT '父页面ID，null=根节点',
    `title`            VARCHAR(500) NOT NULL DEFAULT '' COMMENT '标题',
    `slug_id`          VARCHAR(16) NOT NULL COMMENT '短链ID（对外路由使用）',
    `position`         VARCHAR(64) NOT NULL COMMENT '同级排序（fractional index）',
    `page_type`        VARCHAR(16) NOT NULL DEFAULT 'doc' COMMENT '页面类型：doc富文本 sheet表格 datatable多维表格',
    `content_snapshot` JSON NULL COMMENT 'ProseMirror JSON 快照',
    `html_snapshot`    MEDIUMTEXT NULL COMMENT '渲染HTML快照：只读/搜索/导出',
    `ydoc_state`       LONGBLOB NULL COMMENT 'Yjs二进制，CRDT唯一权威源（Sidecar 直读直写）',
    `creator_id`       BIGINT NOT NULL COMMENT '创建人ID',
    `last_editor_id`   BIGINT NOT NULL COMMENT '最后编辑人ID',
    `edited_at`        DATETIME NULL COMMENT '最后编辑时间',
    `yn`               TINYINT NOT NULL DEFAULT 1 COMMENT '1有效 0删除（软删）',
    `created`          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modified`         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_slug` (`_tenant_id`, `slug_id`),
    KEY `idx_tree` (`_tenant_id`, `space_id`, `parent_id`, `position`),
    KEY `idx_space` (`_tenant_id`, `space_id`, `yn`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档页面表';

-- ========== 版本快照 ==========
CREATE TABLE IF NOT EXISTS `repo_page_version` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `_tenant_id`    BIGINT NOT NULL COMMENT '租户ID',
    `page_id`       BIGINT UNSIGNED NOT NULL COMMENT '页面ID',
    `ydoc_snapshot` LONGBLOB NOT NULL COMMENT '该版本的 Yjs 二进制快照',
    `html`          MEDIUMTEXT NULL COMMENT '该版本渲染HTML快照',
    `editor_id`     BIGINT NOT NULL COMMENT '产生该版本的编辑人ID',
    `reason`        VARCHAR(20) NOT NULL DEFAULT 'autosave' COMMENT 'autosave/manual/restore',
    `created`       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_page` (`_tenant_id`, `page_id`, `created`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档版本快照表';

-- ========== 评论 ==========
CREATE TABLE IF NOT EXISTS `repo_comment` (
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `_tenant_id` BIGINT NOT NULL COMMENT '租户ID',
    `page_id`    BIGINT UNSIGNED NOT NULL COMMENT '页面ID',
    `user_id`    BIGINT NOT NULL COMMENT '评论人ID',
    `parent_id`  BIGINT UNSIGNED NULL COMMENT '父评论ID（一层回复），null=顶层评论',
    `content`    JSON NOT NULL COMMENT '评论富文本JSON',
    `mentions`   JSON NULL COMMENT '被提及user_id列表 → NotifyMessage 站内通知',
    `resolved`   TINYINT NOT NULL DEFAULT 0 COMMENT '0未解决 1已解决',
    `yn`         TINYINT NOT NULL DEFAULT 1 COMMENT '1有效 0删除（软删）',
    `created`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modified`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    KEY `idx_page` (`_tenant_id`, `page_id`, `resolved`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档评论表';

-- ========== 附件映射（对象本体在 file 模块 FileRecord） ==========
CREATE TABLE IF NOT EXISTS `repo_attachment` (
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `_tenant_id` BIGINT NOT NULL COMMENT '租户ID',
    `space_id`   BIGINT NOT NULL COMMENT '所属空间ID',
    `page_id`    BIGINT UNSIGNED NULL COMMENT '关联页面ID，null=空间级附件',
    `user_id`    BIGINT NOT NULL COMMENT '上传人ID',
    `file_key`   VARCHAR(700) NOT NULL COMMENT 'file 模块 FileRecord.fileKey',
    `file_name`  VARCHAR(500) NOT NULL COMMENT '文件名',
    `mime`       VARCHAR(100) NOT NULL COMMENT 'MIME类型',
    `size`       BIGINT NOT NULL COMMENT '文件大小（字节）',
    `yn`         TINYINT NOT NULL DEFAULT 1 COMMENT '1有效 0删除（软删）',
    `created`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_page` (`_tenant_id`, `page_id`),
    KEY `idx_space` (`_tenant_id`, `space_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档附件映射表';

-- ================================================================
-- Sidecar 受限账号（D-03：列级 GRANT，被攻破也碰不到用户/空间/评论数据）
-- 实际密码与库名由 DBA 配置，此处仅作模板，勿直接执行：
--
-- CREATE USER 'collab'@'%' IDENTIFIED BY '<password>';
-- GRANT SELECT, UPDATE (ydoc_state, html_snapshot, content_snapshot, edited_at, last_editor_id)
--   ON <平台库>.repo_page TO 'collab'@'%';
-- -- DELETE 用于 autosave 保留策略（每页仅保留最近 100 条 autosave，manual/restore 不受影响）
-- GRANT INSERT, SELECT, DELETE ON <平台库>.repo_page_version TO 'collab'@'%';
-- FLUSH PRIVILEGES;
-- ================================================================

-- 注意：平台库为多模块共用库，LONGBLOB 写入要求 max_allowed_packet>=128M，
-- 相关参数调整需与 DBA 评估对存量业务的影响。

-- ================================================================
-- 增量变更（存量库执行；CREATE TABLE IF NOT EXISTS 对已存在的表不生效）
-- ================================================================

-- 2026-08-19 repo_page 增加页面类型列（doc/sheet）
ALTER TABLE `repo_page`
    ADD COLUMN `page_type` VARCHAR(16) NOT NULL DEFAULT 'doc' COMMENT '页面类型：doc富文本 sheet表格' AFTER `position`;

-- 2026-08-20 repo_comment 增加评论锚点列（局部文字评论：JSON 字符串
-- { from, to, text, ctxBefore, ctxAfter }，页面级评论为 NULL）
ALTER TABLE `repo_comment`
    ADD COLUMN `anchor` JSON NULL COMMENT '评论锚点：选中文本定位信息（局部文字评论），页面级评论为NULL' AFTER `resolved`;

-- 2026-08-24 新增文档收藏表（门户「我的收藏」）
CREATE TABLE IF NOT EXISTS `repo_page_favorite` (
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `_tenant_id` BIGINT NOT NULL COMMENT '租户ID',
    `user_id`    BIGINT NOT NULL COMMENT '收藏人ID',
    `page_id`    BIGINT UNSIGNED NOT NULL COMMENT '页面ID',
    `created`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '收藏时间',
    `modified`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `yn`         TINYINT NOT NULL DEFAULT 1 COMMENT '1有效 0删除（软删）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_page` (`_tenant_id`, `user_id`, `page_id`),
    KEY `idx_user` (`_tenant_id`, `user_id`, `created`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档收藏表';

-- 2026-08-24 新增文档分享给个人表（需求3：分享到空间之外的按人分享）
CREATE TABLE IF NOT EXISTS `repo_share_user` (
    `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `_tenant_id`     BIGINT NOT NULL COMMENT '租户ID',
    `page_id`        BIGINT UNSIGNED NOT NULL COMMENT '页面ID',
    `target_user_id` BIGINT NOT NULL COMMENT '被分享人用户ID',
    `perm_bits`      INT NOT NULL COMMENT '权限位（同空间分享语义）',
    `created_by`     BIGINT NOT NULL COMMENT '分享发起人ID',
    `created`        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modified`       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `yn`             TINYINT NOT NULL DEFAULT 1 COMMENT '1有效 0删除（软删）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_page_user` (`page_id`, `target_user_id`, `yn`),
    KEY `idx_target_user` (`_tenant_id`, `target_user_id`, `yn`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档分享给个人表';

-- 2026-08-24 repo_share_link 增加链接权限位（需求4：创建链接时选择授予的权限；
-- 只读类：后端强制去除编辑位，缺省仅查看）
ALTER TABLE `repo_share_link`
    ADD COLUMN `perm_bits` INT NOT NULL DEFAULT 1 COMMENT '链接授予权限位（只读类，后端强制去编辑位）';

-- 2026-09-02 分享链接撤销时级联清理：repo_share_user / repo_page_visit 增加 link_id 来源列，
-- 标记「该记录由哪个分享链接产生」；删除链接时按 link_id 精确清理。
-- link_id 为 NULL 表示正常来源（个人分享 / 空间成员直接访问），不受链接删除影响。
ALTER TABLE `repo_share_user`
    ADD COLUMN `link_id` BIGINT UNSIGNED NULL COMMENT '来源分享链接ID（链接访问自动加入与我共享时写入；个人分享覆盖为NULL）' AFTER `created_by`,
    ADD KEY `idx_link_id` (`link_id`, `yn`);

ALTER TABLE `repo_page_visit`
    ADD COLUMN `link_id` BIGINT UNSIGNED NULL COMMENT '来源分享链接ID（通过链接访问时写入；正常访问覆盖为NULL）' AFTER `page_id`,
    ADD KEY `idx_link_id` (`link_id`);

-- 2026-09-02 repo_page 增加正文媒体字节数列（图片/附件/视频/音频 size 合计）。
-- 由 Java 快照回写时解析 content_snapshot 计算落库；列表/树轻量查询直接取列，
-- 避免为算「大小」而把 content_snapshot 大字段整体拉出。存量数据用
-- POST /api/repo/pages/backfill-media-size 一次性回填。
ALTER TABLE `repo_page`
    ADD COLUMN `media_size` BIGINT NOT NULL DEFAULT 0 COMMENT '正文媒体字节数(图片/附件/视频/音频)' AFTER `content_snapshot`;

-- 2026-09-02 分享链接访问日志（append-only，供链接「统计」用）。
-- 与 repo_page_visit 的 upsert 语义不同：每次通过链接访问插一行，用于精确统计
-- 「访问次数」（PV=COUNT）与「去重访问用户 + 最近访问时间」（UV=GROUP BY user_id）。
CREATE TABLE IF NOT EXISTS `repo_share_link_visit` (
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `_tenant_id` BIGINT NOT NULL COMMENT '租户ID',
    `link_id`    BIGINT UNSIGNED NOT NULL COMMENT '分享链接ID',
    `page_id`    BIGINT UNSIGNED NOT NULL COMMENT '页面ID',
    `user_id`    BIGINT NOT NULL COMMENT '访问用户ID',
    `visited_at` DATETIME NOT NULL COMMENT '访问时间',
    `created`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_link_id` (`link_id`, `visited_at`),
    KEY `idx_link_user` (`link_id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分享链接访问日志（统计访问次数与访问用户）';

-- 2026-09-03 文档 AI 编辑会话映射表（用户 × 文档 → AI 编辑会话）。
-- 点击文档标题栏「AI 编辑」时，按 (user_id, page_id) 取会话 id；无则调
-- IConversationRpcService#createRepoDocConversation 创建后落库，实现「同一用户对同一
-- 文档复用同一会话」。slug_id 冗余落库便于排查/对账，唯一键仍锚定 (user_id, page_id)。
CREATE TABLE IF NOT EXISTS `repo_doc_edit_conversation` (
    `id`                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `_tenant_id`           BIGINT NOT NULL COMMENT '租户ID',
    `user_id`              BIGINT NOT NULL COMMENT '编辑用户ID',
    `page_id`              BIGINT UNSIGNED NOT NULL COMMENT '文档页面ID（repo_page.id）',
    `slug_id`              VARCHAR(16) NOT NULL COMMENT '文档短链ID（冗余落库）',
    `edit_conversation_id` BIGINT UNSIGNED NOT NULL COMMENT 'AI 编辑会话ID（agent conversation.id）',
    `created`              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modified`             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `yn`                   TINYINT NOT NULL DEFAULT 1 COMMENT '1有效 0删除（软删）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_page` (`_tenant_id`, `user_id`, `page_id`),
    KEY `idx_page` (`_tenant_id`, `page_id`, `yn`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档 AI 编辑会话映射表（用户×文档 → AI 编辑会话）';
