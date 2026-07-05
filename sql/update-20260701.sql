-- Database schema diff SQL
-- Generated at: 2026-07-01 17:49:46 UTC

-- Added table: cm_content
CREATE TABLE `cm_content`
(
    `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `_tenant_id`        BIGINT       NOT NULL DEFAULT 1 COMMENT '租户ID',
    `title`             VARCHAR(256) NOT NULL COMMENT '标题',
    `content`           MediumText COMMENT '内容',
    `media_url`         VARCHAR(512) COMMENT '多媒体链接地址',
    `is_external_link`  TINYINT      NOT NULL DEFAULT 0 COMMENT '是否为外链',
    `external_link_url` VARCHAR(512) COMMENT '外链地址',
    `is_carousel`       TINYINT      NOT NULL DEFAULT 0 COMMENT '是否轮播展示',
    `category_id`       BIGINT COMMENT '分类ID',
    `created`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modified`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
    `is_home_show`      TINYINT               DEFAULT 0 COMMENT '首页展示',
    PRIMARY KEY (`id`),
    KEY                 `idx_carousel` (`_tenant_id`, `is_carousel`),
    KEY                 `idx_category` (`_tenant_id`, `category_id`),
    KEY                 `idx_tenant` (`_tenant_id`)
);

-- Added table: cm_category
CREATE TABLE `cm_category`
(
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `_tenant_id` BIGINT       NOT NULL DEFAULT 1 COMMENT '租户ID',
    `name`       VARCHAR(128) NOT NULL COMMENT '分类名称',
    `code`       VARCHAR(64)  NOT NULL COMMENT '分类编码',
    `created`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modified`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `idx_tenant_code` (`_tenant_id`, `code`),
    KEY          `idx_tenant` (`_tenant_id`)
);

-- Added table: target_recommend
CREATE TABLE `target_recommend`
(
    `id`            BIGINT      NOT NULL AUTO_INCREMENT,
    `_tenant_id`    BIGINT      NOT NULL DEFAULT 1 COMMENT '商户ID',
    `target_type`   VARCHAR(32) NOT NULL COMMENT '目标类型: Agent, PageApp, Skill, Plugin, Workflow',
    `target_id`     BIGINT      NOT NULL COMMENT '目标对象ID',
    `rec_type`      VARCHAR(32) NOT NULL COMMENT '推荐类型: Home, Official, ChatBoxNav',
    `function_type` VARCHAR(32) COMMENT '功能类型: AgentDev, PageAppDev, SkillDev, PluginDev, Chat',
    `label`         VARCHAR(128) COMMENT '标签名称',
    `icon`          VARCHAR(512) COMMENT '图标',
    `placeholder`   TEXT COMMENT '默认内容, JSON数组格式',
    `sort`          INT         NOT NULL DEFAULT 0 COMMENT '排序',
    `modified`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `created`       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY             `idx_tenant_rec_type` (`_tenant_id`, `rec_type`)
);

-- Added table: eco_market_import_record
CREATE TABLE `eco_market_import_record`
(
    `id`            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `_tenant_id`    BIGINT      NOT NULL COMMENT '租户ID',
    `user_id`       BIGINT      NOT NULL COMMENT '用户ID',
    `space_id`      BIGINT      NOT NULL COMMENT '空间ID',
    `target_type`   VARCHAR(50) NOT NULL COMMENT '目标类型',
    `target_id`     BIGINT      NOT NULL COMMENT '目标ID',
    `eco_target_id` VARCHAR(32) NOT NULL COMMENT '生态市场对应目标ID',
    `created`       DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modified`      DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    KEY             `idx_tenant_space` (`_tenant_id`, `space_id`, `target_type`),
    KEY             `idx_tenant_user` (`_tenant_id`, `user_id`)
);

-- Added table: resource_group_relation
CREATE TABLE `resource_group_relation`
(
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `_tenant_id`  BIGINT      NOT NULL COMMENT '租户ID',
    `target_type` VARCHAR(50) NOT NULL COMMENT '资源类型：Plugin、Workflow',
    `target_id`   BIGINT      NOT NULL COMMENT '资源ID',
    `group_id`    BIGINT      NOT NULL COMMENT '分组ID',
    `created`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modified`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    KEY           `idx_group_id` (`group_id`),
    KEY           `idx_target` (`target_type`, `target_id`)
);

-- Added table: resource_group
CREATE TABLE `resource_group`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `_tenant_id`  BIGINT       NOT NULL COMMENT '租户ID',
    `space_id`    BIGINT       NOT NULL COMMENT '空间ID',
    `creator_id`  BIGINT       NOT NULL COMMENT '创建者ID',
    `name`        VARCHAR(255) NOT NULL COMMENT '分组名称',
    `description` VARCHAR(500) COMMENT '分组名称',
    `icon`        VARCHAR(2000) COMMENT '分组icon',
    `tool_count`  INT          NOT NULL DEFAULT 0 COMMENT '工具数量',
    `type`        VARCHAR(50)  NOT NULL COMMENT '分组类型：Plugin、Workflow',
    `created`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modified`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    KEY           `idx_space_id` (`space_id`, `type`)
);

-- Modified table: agent_component_config
ALTER TABLE `agent_component_config`
    ADD KEY `idx_type` (`type`, `target_id`);
ALTER TABLE `agent_component_config`
    ADD KEY `idx_agent_id` (`agent_id`);

-- Modified table: model_config
ALTER TABLE `model_config`
    ADD COLUMN `sort` INT NOT NULL DEFAULT 0 COMMENT '排序';

-- Modified table: published
ALTER TABLE `published`
    ADD COLUMN `group_id` BIGINT COMMENT '分组id';
ALTER TABLE `published`
    ADD KEY `idx_group_id` (`group_id`);

-- Modified table: agent_config
ALTER TABLE `agent_config`
    ADD COLUMN `enable_version_control` TINYINT NOT NULL DEFAULT 0 COMMENT '是否启用版本控制';
ALTER TABLE `agent_config`
    ADD COLUMN `dev_agent_conversation_id` BIGINT COMMENT '开发Agent的会话ID';
ALTER TABLE `agent_config`
    ADD COLUMN `allow_choose_mode` TINYINT NOT NULL DEFAULT 0 COMMENT '允许选择Agent交互模式';
ALTER TABLE `agent_config`
    ADD COLUMN `sub_type` VARCHAR(32) COMMENT '智能体（应用）子类型';
ALTER TABLE `agent_config`
    ADD COLUMN `publish_version` JSON COMMENT '发布版本记录';
ALTER TABLE `agent_config`
    ADD COLUMN `enable_ask_question` TINYINT NOT NULL DEFAULT 1 COMMENT '打开询问用户';

-- Modified table: workflow_config
ALTER TABLE `workflow_config`
    ADD COLUMN `type` VARCHAR(32) DEFAULT 'Workflow' COMMENT '类型：Workflow和AgentFlow';
ALTER TABLE `workflow_config`
    ADD COLUMN `agent_id` BIGINT COMMENT '类型为AgentFlow时关联的AgentID';

-- Modified table: conversation
ALTER TABLE `conversation`
    ADD COLUMN `dev_target_type` VARCHAR(32) COMMENT '开发的目标对象';
ALTER TABLE `conversation`
    ADD COLUMN `dev_target_id` VARCHAR(32) COMMENT '开发的目标对象ID';
ALTER TABLE `conversation`
    ADD COLUMN `icon` VARCHAR(500) COMMENT '会话主题图标';
ALTER TABLE `conversation`
    ADD COLUMN `dev_space_id` BIGINT COMMENT '开发所在的空间ID';

-- Modified table: pricing_config
ALTER TABLE `pricing_config` MODIFY COLUMN `price` DECIMAL (20,6) COMMENT '价格（单次、买断、包月时有效）';

-- Modified table: knowledge_config
ALTER TABLE `knowledge_config` MODIFY COLUMN `description` VARCHAR (10000) COMMENT '知识库描述';

-- Modified table: bill_order
ALTER TABLE `bill_order` MODIFY COLUMN `amount` DECIMAL (20,6) NOT NULL DEFAULT 0.000000 COMMENT '订单金额';

-- Modified table: custom_table_definition
ALTER TABLE `custom_table_definition` MODIFY COLUMN `table_description` VARCHAR (10000) COMMENT '表描述';

-- Modified table: skill_config
ALTER TABLE `skill_config`
    ADD COLUMN `dev_agent_conversation_id` BIGINT COMMENT '开发会话ID';

-- Modified table: bill_order_item
ALTER TABLE `bill_order_item` MODIFY COLUMN `price` DECIMAL (20,6) NOT NULL DEFAULT 0.000000 COMMENT '单价';

-- Modified table: plugin_config
ALTER TABLE `plugin_config`
    ADD COLUMN `dev_agent_conversation_id` BIGINT COMMENT '开发会话ID';

-- Modified table: workflow_node_config
ALTER TABLE `workflow_node_config`
    ADD KEY `idx_workflow_id` (`workflow_id`);

ALTER TABLE `published` ADD FULLTEXT KEY `ft_name_desc` (`name`, `description`) WITH PARSER ngram;

ALTER TABLE resource_group MODIFY COLUMN icon varchar (2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '分组icon';

CREATE TABLE `knowledge_recall_verification`
(
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键id',
    `kb_id`           BIGINT       NOT NULL COMMENT '文档所属知识库',
    `query_text`      VARCHAR(128) NOT NULL COMMENT '查询内容',
    `_tenant_id`      BIGINT       NOT NULL COMMENT '租户ID',
    `space_id`        BIGINT COMMENT '所属空间ID',
    `created`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `creator_id`      BIGINT COMMENT '创建人id',
    `creator_name`    VARCHAR(64) COMMENT '创建人',
    `modified`        DATETIME              DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    `modified_id`     BIGINT COMMENT '最后修改人id',
    `modified_name`   VARCHAR(64) COMMENT '最后修改人',
    `search_strategy` VARCHAR(255),
    `query_result`    LongText,
    PRIMARY KEY (`id`),
    KEY               `idx_id_kb_id_index` (`space_id`, `kb_id`),
    KEY               `idx_kb_id` (`kb_id`)
);