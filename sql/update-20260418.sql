ALTER TABLE `model_config`
    ADD `usage_scenario` VARCHAR(255) NULL COMMENT '可用的场景范围' AFTER `access_control`;
ALTER TABLE `agent_config`
    ADD `allow_other_model` TINYINT(1) NOT NULL DEFAULT '0' COMMENT '是否允许在对话框中选择其他模型' AFTER `hide_desktop`, ADD `allow_at_skill` TINYINT(1) NOT NULL DEFAULT '1' COMMENT '是否允许@技能' AFTER `allow_other_model`, ADD `allow_private_sandbox` TINYINT(1) NOT NULL DEFAULT '1' COMMENT '是否允许使用自己的电脑' AFTER `allow_at_skill`;
ALTER TABLE `user`
    ADD `lang` VARCHAR(32) NULL COMMENT '用户当前语言环境' AFTER `last_login_time`;
ALTER TABLE `knowledge_config`
    ADD `access_control` TINYINT(1) NOT NULL DEFAULT '0' COMMENT '是否管控 0 不管控；1 管控' AFTER `fulltext_segment_count`;
ALTER TABLE `sys_subject_permission`
    ADD COLUMN `config` JSON COMMENT '配置';
ALTER TABLE `sys_subject_permission`
    ADD COLUMN `subject_key` VARCHAR(255);
ALTER TABLE `sys_subject_permission` MODIFY COLUMN `subject_id` BIGINT COMMENT '主体ID（智能体ID/页面ID）';
--  i18n_lang
CREATE TABLE `i18n_lang`
(
    `id`         BIGINT      NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `_tenant_id` BIGINT      NOT NULL,
    `name`       VARCHAR(64) NOT NULL COMMENT '语言名称，例如 简体中文',
    `lang`       VARCHAR(16) NOT NULL COMMENT '语言，中文：zh-cn，英文:en-us 等等',
    `status`     TINYINT              DEFAULT 1 COMMENT '语言状态，0 停用；1 启用',
    `is_default` TINYINT              DEFAULT 0 COMMENT '是否为默认语言，0 否；1 是',
    `sort`       INT                  DEFAULT 0 COMMENT '排序，值越小越靠前',
    `modified`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    `created`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY          `idx_tenant` (`_tenant_id`)
);

-- i18n_config
CREATE TABLE `i18n_config`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `_tenant_id`  BIGINT       NOT NULL,
    `type`        VARCHAR(32)  NOT NULL COMMENT '类型，包括：系统 System；业务数据 BizData',
    `side`        VARCHAR(32)  NOT NULL COMMENT '端',
    `module`      VARCHAR(32)  NOT NULL COMMENT '模块标记',
    `data_id`     VARCHAR(64)  NOT NULL DEFAULT '-1' COMMENT '类型为BizData时有效',
    `lang`        VARCHAR(16)  NOT NULL COMMENT '语言，中文：zh-cn，英文:en-us 等等',
    `field_key`   VARCHAR(512) NOT NULL COMMENT '键',
    `field_value` MediumText COMMENT '值',
    `remark`      VARCHAR(255) COMMENT '备注',
    `modified`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    `created`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_lang_key` (`_tenant_id`, `lang`, `type`, `side`, `field_key`, `module`, `data_id`)
);