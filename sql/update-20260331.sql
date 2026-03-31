ALTER TABLE `user_access_key` ADD `name` VARCHAR(255) NULL COMMENT '密钥备注名称' AFTER `user_id`;
ALTER TABLE `user_access_key` ADD `expire` DATETIME NULL COMMENT '过期时间，留空为不过期' AFTER `config`, ADD `status` TINYINT(1) NOT NULL DEFAULT '1' COMMENT '状态，启用 1; 停用 0' AFTER `expire`;
ALTER TABLE `schedule_task` ADD `server_info` VARCHAR(32) NULL COMMENT '执行任务的服务器信息' AFTER `error`;
ALTER TABLE `model_config` ADD `usage_scenario` VARCHAR(255) NULL COMMENT '可用的场景范围' AFTER `access_control`;
ALTER TABLE `agent_config` ADD `allow_other_model` TINYINT(1) NOT NULL DEFAULT '0' COMMENT '是否允许在对话框中选择其他模型' AFTER `hide_desktop`, ADD `allow_at_skill` TINYINT(1) NOT NULL DEFAULT '1' COMMENT '是否允许@技能' AFTER `allow_other_model`, ADD `allow_private_sandbox` TINYINT(1) NOT NULL DEFAULT '1' COMMENT '是否允许使用自己的电脑' AFTER `allow_at_skill`;
ALTER TABLE `user` ADD `lang` VARCHAR(32) NULL COMMENT '用户当前语言环境' AFTER `last_login_time`;