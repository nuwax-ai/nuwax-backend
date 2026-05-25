-- Database schema diff SQL
-- Generated at: 2026-05-19 15:48:42 UTC

-- Added table: bill_withdraw_application
CREATE TABLE `bill_withdraw_application`
(
    `id`            BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `_tenant_id`    BIGINT         NOT NULL COMMENT '租户ID',
    `user_id`       BIGINT         NOT NULL COMMENT '用户ID',
    `amount`        DECIMAL(10, 2) NOT NULL COMMENT '提现金额',
    `fee`           DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '平台服务费',
    `actual_amount` DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '实收金额',
    `status`        VARCHAR(50)    NOT NULL DEFAULT 'PENDING_REVIEW' COMMENT '状态：PENDING_REVIEW-待审核，APPROVED-已通过，REJECTED-已驳回，PAID-已打款',
    `reject_reason` VARCHAR(500) COMMENT '驳回原因',
    `payment_extra` JSON COMMENT '打款补充信息',
    `created`       DATETIME                DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modified`      DATETIME                DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    KEY             `idx_status` (`status`),
    KEY             `idx_tenant_id` (`_tenant_id`),
    KEY             `idx_user_id` (`user_id`)
);

-- Added table: pay_developer_account
CREATE TABLE `pay_developer_account`
(
    `id`                      BIGINT NOT NULL AUTO_INCREMENT,
    `_tenant_id`              BIGINT NOT NULL COMMENT '租户',
    `user_id`                 BIGINT NOT NULL COMMENT '用户ID',
    `email`                   VARCHAR(256) COMMENT '邮箱',
    `phone`                   VARCHAR(32) COMMENT '手机号',
    `real_name`               VARCHAR(64) COMMENT '真实姓名',
    `id_card_no`              VARCHAR(32) COMMENT '身份证号',
    `id_card_front_photo_url` VARCHAR(1024) COMMENT '身份证正面照片URL',
    `id_card_back_photo_url`  VARCHAR(1024) COMMENT '身份证反面照片URL',
    `bank_name`               VARCHAR(128) COMMENT '开户银行名称',
    `branch_name`             VARCHAR(256) COMMENT '开户支行名称',
    `bank_card_no`            VARCHAR(64) COMMENT '银行卡号',
    `created`                 DATETIME DEFAULT CURRENT_TIMESTAMP,
    `modified`                DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_user` (`_tenant_id`, `user_id`),
    KEY                       `idx_user` (`user_id`)
);

-- Added table: subscription_plan
CREATE TABLE `subscription_plan`
(
    `id`               BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `_tenant_id`       BIGINT         NOT NULL COMMENT '租户ID',
    `name`             VARCHAR(200)   NOT NULL COMMENT '计划名称',
    `description`      VARCHAR(1000) COMMENT '计划描述',
    `price`            DECIMAL(10, 2) NOT NULL COMMENT '价格',
    `first_price`      DECIMAL(10, 2) COMMENT '首次订阅价格',
    `period`           TINYINT        NOT NULL COMMENT '周期：1-月，3-季度，12-年',
    `credit_amount`    DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '每月赠送积分',
    `call_limit_count` INT            NOT NULL DEFAULT -1 COMMENT '可调用次数，-1表示不限制',
    `function_only`    TINYINT        NOT NULL DEFAULT 0 COMMENT '是否仅为功能订阅：0-否，1-是',
    `is_hot`           TINYINT                 DEFAULT 0 COMMENT '是否热门',
    `status`           TINYINT                 DEFAULT 1 COMMENT '状态：0-下线，1-上线',
    `biz_type`         VARCHAR(50)    NOT NULL COMMENT '业务类型：SYSTEM-系统，AGENT-智能体，SKILL-技能',
    `biz_id`           VARCHAR(100) COMMENT '业务对象ID，非SYSTEM时必填',
    `group_ids`        JSON COMMENT '关联用户组ID（JSON数组）',
    `extra`            JSON COMMENT '扩展字段（JSON）',
    `sort`             BIGINT         NOT NULL DEFAULT 0 COMMENT '排序',
    `created`          DATETIME                DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modified`         DATETIME                DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY                `idx_plan_biz` (`biz_type`, `_tenant_id`, `biz_id`)
);

-- Added table: model_price_tier
CREATE TABLE `model_price_tier`
(
    `id`             BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    `model_id`       BIGINT         NOT NULL COMMENT '模型ID',
    `context_length` INT            NOT NULL COMMENT '上下文长度（如32代表32k）',
    `input_price`    DECIMAL(20, 6) NOT NULL COMMENT '输入价格',
    `output_price`   DECIMAL(20, 6) NOT NULL COMMENT '输出价格',
    `cache_price`    DECIMAL(20, 6) NOT NULL DEFAULT 0.000000 COMMENT '缓存价格',
    `_tenant_id`     BIGINT         NOT NULL COMMENT '租户ID',
    `created`        DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modified`       DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_model_context` (`model_id`, `context_length`, `_tenant_id`)
);

-- Added table: user_credit
CREATE TABLE `user_credit`
(
    `id`            BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `_tenant_id`    BIGINT         NOT NULL COMMENT '租户ID',
    `user_id`       BIGINT         NOT NULL COMMENT '用户ID',
    `batch_no`      VARCHAR(64)    NOT NULL COMMENT '批次号',
    `credit_type`   TINYINT        NOT NULL COMMENT '积分类型：1-订阅积分，2-增购积分，3-活动积分，4-手动发放',
    `total_amount`  DECIMAL(20, 2) NOT NULL COMMENT '总积分',
    `used_amount`   DECIMAL(20, 2) DEFAULT 0.00 COMMENT '已使用积分',
    `remain_amount` DECIMAL(20, 2) NOT NULL COMMENT '剩余积分',
    `expire_time`   DATETIME COMMENT '过期时间，NULL表示永不过期',
    `repaid_amount` DECIMAL(20, 2) DEFAULT 0.00 COMMENT '已还款金额',
    `repay_status`  TINYINT        DEFAULT 0 COMMENT '还款状态：0-未还清，1-已还清',
    `remark`        VARCHAR(500) COMMENT '备注',
    `extra`         JSON COMMENT '扩展信息',
    `version`       INT            DEFAULT 1 COMMENT '乐观锁版本号',
    `modified`      DATETIME       DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    `created`       DATETIME       DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY             `idx_batch_no` (`batch_no`),
    KEY             `idx_expire_time` (`expire_time`),
    KEY             `idx_user_expire` (`user_id`, `expire_time`),
    KEY             `idx_user_id` (`user_id`),
    KEY             `idx_user_type` (`user_id`, `credit_type`)
);

-- Added table: credit_package
CREATE TABLE `credit_package`
(
    `id`            BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `_tenant_id`    BIGINT         NOT NULL COMMENT '租户ID',
    `package_name`  VARCHAR(100)   NOT NULL COMMENT '套餐名称',
    `credit_amount` DECIMAL(20, 2) NOT NULL COMMENT '积分数量',
    `price`         DECIMAL(20, 2) NOT NULL COMMENT '价格',
    `sort`          INT                     DEFAULT 0 COMMENT '排序',
    `status`        TINYINT                 DEFAULT 1 COMMENT '状态：0-禁用，1-启用',
    `remark`        VARCHAR(500) COMMENT '备注',
    `period`        TINYINT        NOT NULL DEFAULT 1 COMMENT '有效期（月）',
    `created`       DATETIME                DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modified`      DATETIME                DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`)
);

-- Added table: bill_resource_stat
CREATE TABLE `bill_resource_stat`
(
    `id`                 BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `_tenant_id`         BIGINT         NOT NULL COMMENT '租户ID',
    `user_id`            BIGINT         NOT NULL COMMENT '用户ID',
    `type`               VARCHAR(50)    NOT NULL COMMENT '类型：CONSUMPTION-消费，SALES-销售',
    `target_type`        VARCHAR(50)    NOT NULL COMMENT '目标类型：Agent-智能体，Model-模型，Workflow-工作流，Plugin-插件',
    `target_id`          BIGINT         NOT NULL COMMENT '目标ID',
    `dt`                 VARCHAR(8)     NOT NULL COMMENT '日期（yyyyMMdd）',
    `call_count`         BIGINT         NOT NULL DEFAULT 0 COMMENT '调用次数',
    `call_failed_count`  BIGINT         NOT NULL DEFAULT 0 COMMENT '调用失败次数',
    `credit_amount`      DECIMAL(30, 6) NOT NULL DEFAULT 0.000000 COMMENT '积分金额',
    `fee_amount`         DECIMAL(30, 6) NOT NULL DEFAULT 0.000000 COMMENT '费用金额',
    `cache_input_tokens` BIGINT         NOT NULL DEFAULT 0 COMMENT '缓存输入Token数',
    `input_tokens`       BIGINT         NOT NULL DEFAULT 0 COMMENT '输入Token数',
    `output_tokens`      BIGINT         NOT NULL DEFAULT 0 COMMENT '输出Token数',
    `extra`              JSON COMMENT '扩展字段',
    `created`            DATETIME                DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modified`           DATETIME                DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    KEY                  `idx_dt` (`dt`),
    KEY                  `idx_target` (`target_type`, `target_id`),
    KEY                  `idx_tenant_user_dt` (`_tenant_id`, `user_id`, `dt`)
);

-- Added table: bill_order
CREATE TABLE `bill_order`
(
    `id`           BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `_tenant_id`   BIGINT         NOT NULL COMMENT '租户ID',
    `user_id`      BIGINT         NOT NULL COMMENT '用户ID',
    `description`  VARCHAR(500) COMMENT '订单描述',
    `biz_type`     VARCHAR(50)    NOT NULL COMMENT '业务类型：CreditPurchase-积分购买，Subscription-订阅',
    `order_status` VARCHAR(50)    NOT NULL DEFAULT 'PENDING' COMMENT '订单状态：PENDING-待支付，PAID-已支付，CANCELLED-已取消',
    `pay_status`   VARCHAR(50)    NOT NULL DEFAULT 'PENDING' COMMENT '支付状态：PENDING-待支付，PROCESSING-处理中，SUCCESS-支付成功，FAILED-支付失败，CLOSED-已关闭',
    `amount`       DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '订单金额',
    `extra`        JSON COMMENT '扩展字段',
    `created`      DATETIME                DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modified`     DATETIME                DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    KEY            `idx_created` (`_tenant_id`, `created`),
    KEY            `idx_order_status` (`_tenant_id`, `order_status`),
    KEY            `idx_pay_status` (`_tenant_id`, `pay_status`),
    KEY            `idx_tenant_id` (`_tenant_id`, `biz_type`),
    KEY            `idx_user_id` (`user_id`, `biz_type`)
);

-- Added table: bill_daily_revenue
CREATE TABLE `bill_daily_revenue`
(
    `id`         BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `_tenant_id` BIGINT         NOT NULL COMMENT '租户ID',
    `user_id`    BIGINT         NOT NULL COMMENT '用户ID',
    `dt`         VARCHAR(8)     NOT NULL COMMENT '日期（yyyyMMdd）',
    `amount`     DECIMAL(30, 6) NOT NULL DEFAULT 0.000000 COMMENT '收益金额',
    `status`     VARCHAR(50)    NOT NULL DEFAULT 'PENDING' COMMENT '结算状态：PENDING-待结算，WITHDRAW_APPLYING-提现申请中，PAYING-打款中，SETTLED-已结算',
    `created`    DATETIME                DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modified`   DATETIME                DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_dt` (`_tenant_id`, `user_id`, `dt`),
    KEY          `idx_status` (`status`),
    KEY          `idx_user_id` (`user_id`)
);

-- Added table: pricing_config
CREATE TABLE `pricing_config`
(
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `space_id`     BIGINT       NOT NULL DEFAULT -1 COMMENT '工作空间ID，-1为系统级',
    `target_type`  VARCHAR(50)  NOT NULL COMMENT '定价对象类型：AGENT/SKILL/KNOWLEDGE/MODEL',
    `target_id`    VARCHAR(100) NOT NULL COMMENT '定价对象ID',
    `pricing_type` VARCHAR(50)  NOT NULL COMMENT '定价类型：ONE_TIME/BUYOUT/MONTHLY/SUBSCRIPTION_PLAN/TIERED',
    `price`        DECIMAL(10, 2) COMMENT '价格（单次、买断、包月时有效）',
    `trial_count`  INT          NOT NULL DEFAULT 0 COMMENT '可试用次数，0=不支持试用',
    `status`       TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0-禁用，1-启用',
    `_tenant_id`   BIGINT       NOT NULL COMMENT '租户ID',
    `created`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modified`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_target` (`target_type`, `target_id`, `_tenant_id`)
);

-- Added table: bill_withdraw_revenue_ref
CREATE TABLE `bill_withdraw_revenue_ref`
(
    `id`             BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `_tenant_id`     BIGINT NOT NULL COMMENT '租户ID',
    `application_id` BIGINT NOT NULL COMMENT '提现申请ID',
    `revenue_id`     BIGINT NOT NULL COMMENT '每日收益ID',
    PRIMARY KEY (`id`),
    KEY              `idx_application_id` (`application_id`),
    KEY              `idx_revenue_id` (`revenue_id`)
);

-- Added table: bill_withdraw_config
CREATE TABLE `bill_withdraw_config`
(
    `id`            BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `_tenant_id`    BIGINT         NOT NULL COMMENT '租户ID',
    `min_amount`    DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '最低提现金额',
    `monthly_limit` INT            NOT NULL DEFAULT 0 COMMENT '每月提现次数限制（0表示不限制）',
    `daily_limit`   INT            NOT NULL DEFAULT 0 COMMENT '每日提现次数限制（0表示不限制）',
    `limit_mode`    VARCHAR(50)    NOT NULL DEFAULT 'ALL' COMMENT '限制模式：ALL-同时满足，ANY-任一满足',
    `created`       DATETIME                DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modified`      DATETIME                DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_id` (`_tenant_id`)
);

-- Added table: credit_flow
CREATE TABLE `credit_flow`
(
    `id`             BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `_tenant_id`     BIGINT         NOT NULL COMMENT '租户ID',
    `user_id`        BIGINT         NOT NULL COMMENT '用户ID',
    `batch_no`       VARCHAR(64) COMMENT '批次号',
    `credit_type`    TINYINT        NOT NULL COMMENT '积分类型：1-订阅积分，2-增购积分，3-活动积分，4-手动发放',
    `operation_type` TINYINT        NOT NULL COMMENT '操作类型：1-增加，2-扣减',
    `amount`         DECIMAL(20, 2) NOT NULL COMMENT '积分数量',
    `before_amount`  DECIMAL(20, 2) NOT NULL COMMENT '操作前积分',
    `after_amount`   DECIMAL(20, 2) NOT NULL COMMENT '操作后积分',
    `biz_no`         VARCHAR(64) COMMENT '业务单号',
    `created`        DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `remark`         VARCHAR(500) COMMENT '备注',
    PRIMARY KEY (`id`),
    KEY              `idx_batch_no` (`batch_no`),
    KEY              `idx_biz_no` (`biz_no`),
    KEY              `idx_created` (`created`),
    KEY              `idx_user_id` (`user_id`)
);

-- Added table: bill_order_item
CREATE TABLE `bill_order_item`
(
    `id`          BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `_tenant_id`  BIGINT         NOT NULL COMMENT '租户ID',
    `order_id`    BIGINT         NOT NULL COMMENT '订单ID',
    `target_type` VARCHAR(50)    NOT NULL COMMENT '目标类型：Plan-订阅计划，CreditPackage-积分套餐',
    `target_name` VARCHAR(200) COMMENT '目标名称',
    `target_id`   BIGINT         NOT NULL COMMENT '目标ID',
    `price`       DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '单价',
    `count`       INT            NOT NULL DEFAULT 1 COMMENT '数量',
    `snapshot`    JSON COMMENT '快照',
    `created`     DATETIME                DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modified`    DATETIME                DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    KEY           `idx_order_id` (`order_id`)
);

-- Added table: trial_record
CREATE TABLE `trial_record`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT       NOT NULL COMMENT '用户ID',
    `target_type` VARCHAR(50)  NOT NULL COMMENT '业务类型',
    `target_id`   VARCHAR(100) NOT NULL COMMENT '业务对象ID',
    `used_count`  INT          NOT NULL DEFAULT 0 COMMENT '已使用次数',
    `_tenant_id`  BIGINT       NOT NULL COMMENT '租户ID',
    `created`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modified`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_target` (`user_id`, `target_type`, `target_id`, `_tenant_id`)
);

-- Added table: user_subscription
CREATE TABLE `user_subscription`
(
    `id`              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `_tenant_id`      BIGINT      NOT NULL COMMENT '租户ID',
    `user_id`         BIGINT      NOT NULL COMMENT '用户ID',
    `plan_id`         BIGINT      NOT NULL COMMENT '计划ID',
    `biz_type`        VARCHAR(32) NOT NULL COMMENT '业务类型',
    `biz_id`          VARCHAR(32) NOT NULL DEFAULT '-1' COMMENT '业务ID',
    `period`          TINYINT     NOT NULL COMMENT '订阅周期类型',
    `start_time`      DATETIME    NOT NULL COMMENT '开始时间',
    `end_time`        DATETIME    NOT NULL COMMENT '结束时间',
    `status`          TINYINT              DEFAULT 0 COMMENT '状态：0-生效中，1-已过期，2-已取消',
    `call_used_count` INT         NOT NULL DEFAULT 0 COMMENT '已使用调用次数',
    `next_reset_time` DATETIME COMMENT '下次重置时间',
    `extra`           JSON COMMENT '扩展数据',
    `created`         DATETIME             DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modified`        DATETIME             DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_plan` (`user_id`, `plan_id`),
    KEY               `idx_biz_type` (`biz_type`),
    KEY               `idx_end_time` (`end_time`),
    KEY               `idx_next_reset_time` (`next_reset_time`),
    KEY               `idx_plan_id` (`plan_id`),
    KEY               `idx_user_biz` (`user_id`, `biz_type`, `biz_id`)
);

-- Added table: model_provider
CREATE TABLE `model_provider`
(
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `_tenant_id` BIGINT       NOT NULL COMMENT '租户ID',
    `pid`        VARCHAR(64)  NOT NULL COMMENT '提供商ID',
    `name`       VARCHAR(200) NOT NULL COMMENT '提供商名称',
    `icon`       VARCHAR(500) COMMENT '图标',
    `api_info`   JSON COMMENT 'API信息',
    `created`    DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modified`   DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    KEY          `idx_tenant_id` (`_tenant_id`)
);

-- Added table: pay_order
CREATE TABLE `pay_order`
(
    `id`                       BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `_tenant_id`               BIGINT       NOT NULL COMMENT '租户ID',
    `biz_order_no`             VARCHAR(128) NOT NULL COMMENT '业务订单号',
    `biz_scene`                VARCHAR(64) COMMENT '业务场景',
    `order_amount`             BIGINT       NOT NULL COMMENT '订单金额（分）',
    `subject`                  VARCHAR(512) COMMENT '摘要',
    `ext`                      JSON COMMENT '扩展',
    `pay_mode`                 VARCHAR(32)  NOT NULL COMMENT '支付模式：scan',
    `pay_channel`              VARCHAR(32) COMMENT '支付渠道：WxPay / AliPay / UnionPay',
    `platform_fee`             BIGINT COMMENT '平台费（分）',
    `provider_fee`             BIGINT COMMENT '通道费（分）',
    `net_amount`               BIGINT COMMENT '净额（分）',
    `gateway_payment_order_no` VARCHAR(128) COMMENT '网关支付单号',
    `gateway_sync_status`      VARCHAR(32)  NOT NULL COMMENT '网关同步：PENDING / SUCCESS / FAILED',
    `gateway_last_error`       VARCHAR(2000) COMMENT '网关最近一次错误信息',
    `gateway_order_status`     VARCHAR(64) COMMENT '网关订单状态',
    `biz_notify_status`        VARCHAR(32) COMMENT '业务通知：POLLING / NOTIFIED / TIMEOUT',
    `paid_at`                  DATETIME COMMENT '支付成功时间',
    `created`                  DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modified`                 DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    KEY                        `idx_tenant_biz_id` (`_tenant_id`, `biz_order_no`, `id`),
    KEY                        `idx_tenant_gateway` (`_tenant_id`, `gateway_payment_order_no`)
);

-- Added table: bill_revenue_detail
CREATE TABLE `bill_revenue_detail`
(
    `id`          BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `_tenant_id`  BIGINT         NOT NULL COMMENT '租户ID',
    `user_id`     BIGINT         NOT NULL COMMENT '用户ID',
    `dt`          VARCHAR(8)     NOT NULL COMMENT '日期（yyyyMMdd）',
    `amount`      DECIMAL(30, 6) NOT NULL DEFAULT 0.000000 COMMENT '金额',
    `type`        VARCHAR(50)    NOT NULL COMMENT '类型：Plan-计划购买，ModelCall-模型调用，ToolCall-工具调用',
    `type_id`     BIGINT COMMENT '类型关联ID（Plan时为订阅计划ID）',
    `order_id`    BIGINT COMMENT '关联订单ID',
    `target_type` VARCHAR(50) COMMENT '目标类型：Agent/Skill/Model/Plugin/Mcp/Workflow',
    `target_id`   BIGINT COMMENT '目标ID',
    `remark`      VARCHAR(500) COMMENT '备注',
    `extra`       JSON COMMENT '扩展字段（如模型token使用等）',
    `biz_no`      VARCHAR(100) COMMENT '业务单号（幂等性保证，相同bizNo不会重复记录）',
    `created`     DATETIME                DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modified`    DATETIME                DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_biz_no` (`biz_no`),
    KEY           `idx_dt` (`dt`),
    KEY           `idx_order_id` (`order_id`),
    KEY           `idx_target` (`target_type`, `target_id`),
    KEY           `idx_type` (`type`),
    KEY           `idx_user_id` (`user_id`)
);

-- Modified table: publish_apply
ALTER TABLE `publish_apply`
    ADD KEY `idx_target` (`_tenant_id`, `target_type`, `target_id`);
-- ⚠️  Warning: index `_tenant_id` does not exist in the new version; no drop statement was generated for data safety
-- To delete it, run manually: ALTER TABLE `publish_apply` DROP KEY `_tenant_id`;


-- Database schema diff SQL
-- Generated at: 2026-05-22 11:03:41 UTC

-- Added table: document_parse_status_record
CREATE TABLE `document_parse_status_record`
(
    `id`                 BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `create_time`        DATETIME COMMENT '创建时间',
    `execute_state`      VARCHAR(20) COMMENT '执行状态（1:执行成功、2：执行失败、0进行中）',
    `success_count`      BIGINT COMMENT '成功条数',
    `error_count`        BIGINT COMMENT '失败条数',
    `start_time`         DATETIME COMMENT '执行开始时间',
    `end_time`           DATETIME COMMENT '执行结束时间',
    `execute_content`    VARCHAR(800) COMMENT '存放完成了哪些知识库同步，需要包含同步成功或失败的状态',
    `execute_error_logs` VARCHAR(800) COMMENT '执行的异常日志',
    `_tenant_id`         BIGINT,
    PRIMARY KEY (`id`)
);

-- Added table: document_parse_status
CREATE TABLE `document_parse_status`
(
    `id`            BIGINT  NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `document_id`   BIGINT  NOT NULL COMMENT '文档ID（逻辑外键，关联knowledge_document.id）',
    `kb_id`         BIGINT  NOT NULL COMMENT '知识库ID',
    `triple_status` TINYINT NOT NULL DEFAULT 0 COMMENT '三元组解析状态：0-未开始，1-进行中，2-成功，3-禁用，10-失败',
    `_tenant_id`    BIGINT COMMENT '租户ID',
    `created`       DATETIME         DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modified`      DATETIME         DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    `task_id`       BIGINT COMMENT '任务编号',
    PRIMARY KEY (`id`),
    KEY             `idx_document_id` (`document_id`),
    KEY             `idx_kb_id` (`kb_id`)
);

-- Modified table: user
ALTER TABLE `user` MODIFY COLUMN `status` VARCHAR (16) NOT NULL DEFAULT 'Enabled' COMMENT '状态，启用或禁用';

-- Modified table: pay_order
ALTER TABLE `pay_order`
    ADD KEY `idx_tenant_created_id` (`_tenant_id`, `created`, `id`);
ALTER TABLE `pay_order`
    ADD KEY `idx_sync_fail_scan` (`gateway_sync_status`, `biz_notify_status`, `modified`, `id`);
ALTER TABLE `pay_order`
    ADD KEY `idx_reconcile_scan` (`gateway_sync_status`, `gateway_order_status`, `modified`, `id`);
ALTER TABLE `pay_order`
    ADD UNIQUE KEY `uk_tenant_biz_order` (`_tenant_id`, `biz_order_no`);