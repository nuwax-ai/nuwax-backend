CREATE TABLE `im_channel_config`
(
    `id`            bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `channel`       varchar(64)  NOT NULL COMMENT '渠道类型',
    `target_type`   varchar(64)  NOT NULL COMMENT '渠道目标类型',
    `target_id`     varchar(255) NOT NULL COMMENT '渠道目标唯一标识',
    `user_id`       bigint(20) NOT NULL COMMENT '关联系统用户ID',
    `agent_id`      bigint(20) NOT NULL COMMENT '关联智能体ID',
    `config_data`   text         NOT NULL COMMENT '渠道专有配置（JSON 字符串）',
    `output_mode`   varchar(32)           DEFAULT NULL COMMENT '输出方式',
    `enabled`       tinyint(1) DEFAULT '1' COMMENT '是否启用',
    `name`          varchar(255)          DEFAULT NULL COMMENT '配置名称备注',
    `_tenant_id`    bigint(20) NOT NULL COMMENT '租户ID',
    `space_id`      bigint(20) DEFAULT NULL COMMENT '空间ID',
    `created`       datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `creator_id`    bigint(20) DEFAULT NULL COMMENT '创建人ID',
    `creator_name`  varchar(64)           DEFAULT NULL COMMENT '创建人',
    `modified`      datetime              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `modified_id`   bigint(20) DEFAULT NULL COMMENT '最后修改人ID',
    `modified_name` varchar(64)           DEFAULT NULL COMMENT '最后修改人',
    `yn`            tinyint(4) NOT NULL DEFAULT '1' COMMENT '逻辑标记,1:有效;-1:无效',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_channel_target` (`channel`,`target_type`,`target_id`,`yn`),
    KEY             `idx_tenant_space_channel_target` (`_tenant_id`,`space_id`,`channel`,`target_type`)
) ENGINE=InnoDB AUTO_INCREMENT=50 DEFAULT CHARSET=utf8mb4 COMMENT='IM 渠道配置';
CREATE TABLE `im_session`
(
    `id`              bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `channel`         varchar(20)  NOT NULL COMMENT '渠道类型',
    `target_type`     varchar(32)           DEFAULT NULL COMMENT '渠道目标类型',
    `session_key`     varchar(255) NOT NULL COMMENT '会话标识：单聊为用户ID，群聊为群ID',
    `session_name`    varchar(255)          DEFAULT NULL COMMENT '会话用户名：单聊用户名/昵称，群聊群名',
    `chat_type`       varchar(20)  NOT NULL COMMENT '会话类型：private-私聊、group-群聊',
    `user_id`         bigint(20) NOT NULL COMMENT '系统用户ID',
    `agent_id`        bigint(20) NOT NULL COMMENT '智能体ID',
    `conversation_id` bigint(20) NOT NULL COMMENT '系统会话ID',
    `_tenant_id`      bigint(20) NOT NULL COMMENT '租户ID',
    `create_time`     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_channel_target_session_key_agent_id` (`channel`,`target_type`,`session_key`,`agent_id`,`_tenant_id`)
) ENGINE=InnoDB AUTO_INCREMENT=70 DEFAULT CHARSET=utf8mb4 COMMENT='IM会话表';