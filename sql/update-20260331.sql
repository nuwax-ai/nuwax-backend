ALTER TABLE `user_access_key` ADD `name` VARCHAR(255) NULL COMMENT '密钥备注名称' AFTER `user_id`;
ALTER TABLE `user_access_key` ADD `expire` DATETIME NULL COMMENT '过期时间，留空为不过期' AFTER `config`, ADD `status` TINYINT(1) NOT NULL DEFAULT '1' COMMENT '状态，启用 1; 停用 0' AFTER `expire`;
ALTER TABLE `schedule_task` ADD `server_info` VARCHAR(32) NULL COMMENT '执行任务的服务器信息' AFTER `error`;