SET SQL_MODE = "NO_AUTO_VALUE_ON_ZERO";
START TRANSACTION;
SET time_zone = "+00:00";


/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!40101 SET NAMES utf8mb4 */;

--
-- 数据库： `agent_platform`
--

-- --------------------------------------------------------

--
-- 表的结构 `agent_component_config`
--

CREATE TABLE `agent_component_config` (
  `id` bigint(20) NOT NULL,
  `_tenant_id` bigint(20) NOT NULL DEFAULT '1' COMMENT '商户ID',
  `name` varchar(64) DEFAULT NULL COMMENT '节点名称',
  `icon` varchar(255) DEFAULT NULL COMMENT '组件图标',
  `description` text COMMENT '组件描述',
  `agent_id` bigint(20) DEFAULT NULL COMMENT 'AgentID',
  `type` varchar(64) NOT NULL COMMENT '组件类型',
  `target_id` bigint(20) DEFAULT NULL COMMENT '关联的组件ID',
  `bind_config` json DEFAULT NULL COMMENT '组件绑定配置',
  `exception_out` tinyint(4) NOT NULL DEFAULT '0' COMMENT '异常是否抛出，中断主要流程',
  `fallback_msg` text COMMENT '异常时兜底内容',
  `modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体组件配置';

-- --------------------------------------------------------

--
-- 表的结构 `agent_config`
--

CREATE TABLE `agent_config` (
  `id` bigint(20) NOT NULL COMMENT '智能体ID',
  `uid` varchar(64) NOT NULL COMMENT 'agent唯一标识',
  `_tenant_id` bigint(20) NOT NULL DEFAULT '-1' COMMENT '商户ID',
  `space_id` bigint(20) DEFAULT NULL COMMENT '空间ID',
  `creator_id` bigint(20) NOT NULL COMMENT '创建者ID',
  `name` varchar(64) NOT NULL COMMENT 'Agent名称',
  `description` text COMMENT '描述信息',
  `icon` varchar(255) DEFAULT NULL COMMENT '图标地址',
  `system_prompt` text COMMENT '系统提示词',
  `user_prompt` text COMMENT '用户消息提示词，{{AGENT_USER_MSG}}引用用户消息',
  `open_suggest` enum('Open','Close') NOT NULL DEFAULT 'Open' COMMENT '是否开启问题建议',
  `suggest_prompt` text COMMENT '用户问题建议',
  `opening_chat_msg` text COMMENT '首次打开聊天框自动回复消息',
  `opening_guid_question` json DEFAULT NULL COMMENT '开场引导问题',
  `open_long_memory` enum('Open','Close') NOT NULL DEFAULT 'Open' COMMENT '是否开启长期记忆',
  `open_scheduled_task` varchar(32) DEFAULT NULL COMMENT '开启定时任务',
  `publish_status` varchar(32) NOT NULL DEFAULT 'Developing' COMMENT 'Agent发布状态',
  `dev_conversation_id` bigint(20) DEFAULT NULL,
  `yn` tinyint(4) NOT NULL DEFAULT '0' COMMENT '逻辑删除，1为删除',
  `modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `hide_chat_area` tinyint(4) NOT NULL DEFAULT '0' COMMENT '隐藏对话框',
  `extra` json DEFAULT NULL COMMENT '扩展信息',
  `access_control` tinyint(4) NOT NULL DEFAULT '0' COMMENT '是否受权限管控，0 不受管控；1 受控',
  `hide_desktop` tinyint(1) NOT NULL DEFAULT '0' COMMENT '远程桌面展示控制：0 不隐藏；1 隐藏',
  `type` varchar(32) NOT NULL DEFAULT 'ChatBot' COMMENT '智能体类型',
  `expand_page_area` tinyint(4) NOT NULL DEFAULT '0' COMMENT '默认展开页面区域'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `agent_temp_chat`
--

CREATE TABLE `agent_temp_chat` (
  `id` bigint(20) NOT NULL,
  `_tenant_id` bigint(20) NOT NULL,
  `user_id` bigint(20) NOT NULL COMMENT '创建链接的用户ID',
  `agent_id` bigint(20) NOT NULL,
  `chat_key` varchar(64) NOT NULL COMMENT '临时会话标识',
  `require_login` tinyint(4) NOT NULL DEFAULT '1' COMMENT '是否需要登录 1 是，0 否',
  `expire` datetime DEFAULT NULL,
  `modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `card`
--

CREATE TABLE `card` (
  `id` bigint(20) NOT NULL,
  `card_key` varchar(32) NOT NULL COMMENT '卡片唯一标识，与前端组件做关联',
  `name` varchar(64) NOT NULL COMMENT '卡片名称',
  `image_url` varchar(255) DEFAULT NULL COMMENT '卡片示例图片地址',
  `args` json DEFAULT NULL COMMENT '卡片参数',
  `modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `category`
--

CREATE TABLE `category` (
  `id` bigint(20) NOT NULL COMMENT '主键ID',
  `_tenant_id` bigint(20) NOT NULL COMMENT '租户ID',
  `name` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '分类名称',
  `description` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '分类描述',
  `code` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '分类编码',
  `type` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '分类类型：Agent、PageApp、Component',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分类管理表';

-- --------------------------------------------------------

--
-- 表的结构 `config_history`
--

CREATE TABLE `config_history` (
  `id` bigint(20) NOT NULL,
  `_tenant_id` bigint(20) NOT NULL,
  `op_user_id` bigint(20) DEFAULT NULL COMMENT '操作用户',
  `target_id` bigint(20) NOT NULL COMMENT '目标对象ID',
  `target_type` enum('Agent','Plugin','Workflow','Skill') NOT NULL COMMENT '目标对象类型',
  `type` varchar(64) NOT NULL COMMENT '历史记录类型',
  `config` json DEFAULT NULL COMMENT '当时的配置',
  `description` varchar(255) DEFAULT NULL COMMENT '变更描述',
  `modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `content_i18n`
--

CREATE TABLE `content_i18n` (
  `id` bigint(20) NOT NULL COMMENT 'ID',
  `model` varchar(32) NOT NULL COMMENT '业务模块标记',
  `mid` varchar(32) NOT NULL COMMENT '业务模块ID',
  `lang` varchar(16) NOT NULL COMMENT '语言，中文：zh-cn，英文:en-us',
  `field_key` varchar(64) NOT NULL COMMENT '业务表字段',
  `content` mediumtext COMMENT '具体内容',
  `modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容国际化表';

-- --------------------------------------------------------

--
-- 表的结构 `conversation`
--

CREATE TABLE `conversation` (
  `id` bigint(20) NOT NULL,
  `_tenant_id` bigint(20) NOT NULL COMMENT '商户ID',
  `uid` varchar(64) NOT NULL COMMENT '会话唯一标识',
  `user_id` bigint(20) NOT NULL COMMENT '用户ID',
  `agent_id` bigint(20) NOT NULL COMMENT '智能体ID',
  `topic` varchar(255) NOT NULL COMMENT '主题',
  `summary` mediumtext COMMENT '汇总',
  `variables` json DEFAULT NULL COMMENT '用户输入的变量值',
  `dev_mode` tinyint(4) NOT NULL DEFAULT '0',
  `topic_updated` tinyint(4) NOT NULL DEFAULT '0',
  `type` varchar(32) NOT NULL DEFAULT 'Chat' COMMENT '会话类型，Chat对话；Task 定时任务',
  `task_id` varchar(64) DEFAULT NULL COMMENT '对应的任务ID',
  `task_status` varchar(32) DEFAULT NULL COMMENT '任务状态',
  `task_cron` varchar(32) DEFAULT NULL COMMENT '任务配置',
  `sandbox_server_id` varchar(64) DEFAULT NULL COMMENT 'agent沙箱服务器编码',
  `sandbox_session_id` varchar(64) DEFAULT NULL COMMENT '保存沙箱agent返回的session_id',
  `modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话表';

-- --------------------------------------------------------

--
-- 表的结构 `conversation_message`
--

CREATE TABLE `conversation_message` (
  `id` bigint(20) NOT NULL COMMENT '主键ID',
  `_tenant_id` bigint(20) DEFAULT NULL COMMENT '租户ID',
  `user_id` bigint(20) DEFAULT NULL COMMENT '用户ID',
  `agent_id` bigint(20) DEFAULT NULL COMMENT '智能体ID',
  `conversation_id` bigint(20) DEFAULT NULL COMMENT '会话ID',
  `message_id` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '消息ID',
  `content` mediumtext COLLATE utf8mb4_unicode_ci COMMENT '消息内容',
  `modified` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `created` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对话消息表';

-- --------------------------------------------------------

--
-- 表的结构 `custom_field_definition`
--

CREATE TABLE `custom_field_definition` (
  `id` bigint(20) NOT NULL COMMENT '主键ID',
  `_tenant_id` bigint(20) NOT NULL COMMENT '租户ID',
  `space_id` bigint(20) NOT NULL COMMENT '所属空间ID',
  `table_id` bigint(20) NOT NULL COMMENT '关联的表ID',
  `field_name` varchar(64) NOT NULL COMMENT '字段名',
  `field_description` varchar(200) DEFAULT NULL COMMENT '字段描述',
  `field_type` tinyint(4) NOT NULL DEFAULT '1' COMMENT '字段类型：1:String;2:Integer;3:Number;4:Boolean;5:Date',
  `nullable_flag` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否可为空：1-可空 -1-非空',
  `default_value` varchar(255) DEFAULT NULL COMMENT '默认值',
  `unique_flag` tinyint(1) NOT NULL DEFAULT '-1' COMMENT '是否唯一：1-唯一 -1-非唯一',
  `enabled_flag` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用：1-启用 -1-禁用',
  `sort_index` int(11) NOT NULL COMMENT '字段顺序',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `creator_id` bigint(20) DEFAULT NULL COMMENT '创建人id',
  `creator_name` varchar(64) DEFAULT NULL COMMENT '创建人',
  `modified` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `modified_id` bigint(20) DEFAULT NULL COMMENT '最后修改人id',
  `modified_name` varchar(64) DEFAULT NULL COMMENT '最后修改人',
  `yn` tinyint(4) DEFAULT '1' COMMENT '逻辑标记,1:有效;-1:无效',
  `system_field_flag` tinyint(4) NOT NULL DEFAULT '-1' COMMENT '是否系统字段;1:系统字段;-1:否',
  `field_str_len` int(11) DEFAULT NULL COMMENT '字符串字段长度,可空,比如字符串,可以指定长度使用'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='自定义字段定义';

-- --------------------------------------------------------

--
-- 表的结构 `custom_page_build`
--

CREATE TABLE `custom_page_build` (
  `id` bigint(20) NOT NULL COMMENT '主键ID',
  `project_id` bigint(20) NOT NULL COMMENT '项目ID',
  `dev_running` tinyint(4) NOT NULL DEFAULT '-1' COMMENT '开发服务器运行标记,1:运行中;-1:未运行',
  `dev_pid` int(11) DEFAULT NULL COMMENT '开发服务器进程ID',
  `dev_port` int(11) DEFAULT NULL COMMENT '开发服务器端口号',
  `last_keep_alive_time` datetime DEFAULT NULL COMMENT '最后保活时间',
  `build_running` tinyint(4) NOT NULL DEFAULT '-1' COMMENT '线上运行标记,1:运行中;-1:未运行',
  `build_time` datetime DEFAULT NULL COMMENT '构建发布时间',
  `build_version` int(11) DEFAULT NULL COMMENT '发布的版本号',
  `code_version` int(11) NOT NULL COMMENT '代码版本',
  `version_info` json DEFAULT NULL COMMENT '版本信息',
  `last_chat_model_id` bigint(20) DEFAULT NULL COMMENT '上次对话模型ID',
  `last_multi_model_id` bigint(20) DEFAULT NULL COMMENT '上次多模态模型ID',
  `_tenant_id` bigint(20) NOT NULL COMMENT '租户ID',
  `space_id` bigint(20) DEFAULT NULL COMMENT '空间ID',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `creator_id` bigint(20) DEFAULT NULL COMMENT '创建人ID',
  `creator_name` varchar(64) DEFAULT NULL COMMENT '创建人',
  `modified` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  `modified_id` bigint(20) DEFAULT NULL COMMENT '最后修改人ID',
  `modified_name` varchar(64) DEFAULT NULL COMMENT '最后修改人',
  `yn` tinyint(4) NOT NULL DEFAULT '1' COMMENT '逻辑标记,1:有效;-1:无效'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `custom_page_config`
--

CREATE TABLE `custom_page_config` (
  `id` bigint(20) NOT NULL COMMENT '主键ID',
  `name` varchar(255) NOT NULL COMMENT '项目名称',
  `description` text COMMENT '描述信息',
  `icon` varchar(500) DEFAULT NULL COMMENT '项目图标',
  `cover_img` varchar(500) DEFAULT NULL COMMENT '封面图片',
  `cover_img_source_type` varchar(500) DEFAULT NULL COMMENT '封面图片来源',
  `base_path` varchar(255) NOT NULL COMMENT '项目基础路径',
  `build_running` tinyint(4) NOT NULL COMMENT '线上运行标记,1:运行中;-1:未运行',
  `publish_type` varchar(100) DEFAULT NULL,
  `need_login` tinyint(4) DEFAULT NULL COMMENT '是否需要登陆,1:需要',
  `dev_agent_id` bigint(20) DEFAULT NULL COMMENT '开发关联智能体ID',
  `project_type` varchar(100) NOT NULL COMMENT '项目类型',
  `proxy_config` json DEFAULT NULL COMMENT '代理配置',
  `page_arg_config` json DEFAULT NULL COMMENT '路径参数配置',
  `data_sources` json DEFAULT NULL COMMENT '绑定的数据源',
  `ext` json DEFAULT NULL COMMENT '扩展参数',
  `_tenant_id` bigint(20) NOT NULL COMMENT '租户ID',
  `space_id` bigint(20) DEFAULT NULL COMMENT '空间ID',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `creator_id` bigint(20) DEFAULT NULL COMMENT '创建人ID',
  `creator_name` varchar(64) DEFAULT NULL COMMENT '创建人',
  `modified` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  `modified_id` bigint(20) DEFAULT NULL COMMENT '最后修改人ID',
  `modified_name` varchar(64) DEFAULT NULL COMMENT '最后修改人',
  `yn` tinyint(4) NOT NULL DEFAULT '1' COMMENT '逻辑标记,1:有效;-1:无效'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `custom_page_conversation`
--

CREATE TABLE `custom_page_conversation` (
  `id` bigint(20) NOT NULL COMMENT '主键ID',
  `project_id` bigint(20) NOT NULL COMMENT '项目ID',
  `topic` varchar(500) DEFAULT NULL COMMENT '会话主题',
  `content` longtext NOT NULL COMMENT '会话内容',
  `_tenant_id` bigint(20) NOT NULL COMMENT '租户ID',
  `space_id` bigint(20) DEFAULT NULL COMMENT '空间ID',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `creator_id` bigint(20) DEFAULT NULL COMMENT '创建者ID',
  `creator_name` varchar(255) DEFAULT NULL COMMENT '创建者名称',
  `modified` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
  `modified_id` bigint(20) DEFAULT NULL COMMENT '修改者ID',
  `modified_name` varchar(255) DEFAULT NULL COMMENT '修改者名称',
  `yn` int(11) NOT NULL DEFAULT '1' COMMENT '是否有效 1:有效 -1:无效'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `custom_page_domain`
--

CREATE TABLE `custom_page_domain` (
  `id` bigint(20) NOT NULL COMMENT '主键',
  `_tenant_id` bigint(20) NOT NULL COMMENT '租户ID',
  `project_id` bigint(20) NOT NULL COMMENT '项目ID',
  `domain` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '域名',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='自定义页面域名绑定表';

-- --------------------------------------------------------

--
-- 表的结构 `custom_table_definition`
--

CREATE TABLE `custom_table_definition` (
  `id` bigint(20) NOT NULL COMMENT '主键ID',
  `_tenant_id` bigint(20) NOT NULL COMMENT '租户ID',
  `space_id` bigint(20) NOT NULL COMMENT '所属空间ID',
  `icon` varchar(255) DEFAULT NULL COMMENT '图标图片地址',
  `table_name` varchar(64) NOT NULL COMMENT '表名',
  `table_description` varchar(256) DEFAULT NULL COMMENT '表描述',
  `doris_database` varchar(64) NOT NULL COMMENT 'Doris数据库名',
  `doris_table` varchar(64) NOT NULL COMMENT 'Doris表名',
  `status` tinyint(4) NOT NULL DEFAULT '1' COMMENT '状态：1-启用 -1-禁用',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `creator_id` bigint(20) DEFAULT NULL COMMENT '创建人id',
  `creator_name` varchar(64) DEFAULT NULL COMMENT '创建人',
  `modified` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `modified_id` bigint(20) DEFAULT NULL COMMENT '最后修改人id',
  `modified_name` varchar(64) DEFAULT NULL COMMENT '最后修改人',
  `yn` tinyint(4) DEFAULT '1' COMMENT '逻辑标记,1:有效;-1:无效'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='自定义数据表定义';

-- --------------------------------------------------------

--
-- 表的结构 `eco_market_client_config`
--

CREATE TABLE `eco_market_client_config` (
  `id` bigint(20) NOT NULL COMMENT '主键id',
  `uid` varchar(128) NOT NULL COMMENT '唯一ID,分布式唯一UUID',
  `name` varchar(128) NOT NULL COMMENT '名称',
  `description` varchar(256) DEFAULT NULL COMMENT '描述',
  `data_type` tinyint(4) NOT NULL DEFAULT '1' COMMENT '市场类型,默认插件,1:插件;2:模板;3:MCP',
  `target_type` varchar(64) DEFAULT NULL COMMENT '细分类型,比如: 插件,智能体,工作流',
  `target_id` bigint(20) DEFAULT NULL COMMENT '具体目标的id,可以智能体,工作流,插件,还有mcp等',
  `category_code` varchar(128) DEFAULT NULL COMMENT '分类编码,商业服务等,通过接口获取',
  `category_name` varchar(128) DEFAULT NULL COMMENT '分类名称,商业服务等,通过接口获取',
  `owned_flag` tinyint(4) NOT NULL DEFAULT '0' COMMENT '是否我的分享,0:否(生态市场获取的);1:是(我的分享)',
  `share_status` tinyint(4) NOT NULL DEFAULT '1' COMMENT '分享状态,1:草稿;2:审核中;3:已发布;4:已下线;5:驳回',
  `use_status` tinyint(4) NOT NULL DEFAULT '2' COMMENT '使用状态,1:启用;2:禁用;',
  `publish_time` datetime DEFAULT NULL COMMENT '发布时间',
  `offline_time` datetime DEFAULT NULL COMMENT '下线时间',
  `version_number` bigint(20) NOT NULL DEFAULT '1' COMMENT '版本号,自增,发布一次增加1,初始值为1',
  `author` varchar(256) DEFAULT NULL COMMENT '作者信息',
  `publish_doc` mediumtext COMMENT '发布文档',
  `config_param_json` json DEFAULT NULL COMMENT '请求参数配置json',
  `config_json` json DEFAULT NULL COMMENT '配置json,存储插件的配置信息如果有其他额外的信息保存放这里',
  `icon` varchar(255) DEFAULT NULL COMMENT '图标图片地址',
  `_tenant_id` bigint(20) NOT NULL COMMENT '租户ID',
  `create_client_id` varchar(128) NOT NULL COMMENT '创建者的客户端ID',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `creator_id` bigint(20) DEFAULT NULL COMMENT '创建人id',
  `creator_name` varchar(64) DEFAULT NULL COMMENT '创建人',
  `modified` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `modified_id` bigint(20) DEFAULT NULL COMMENT '最后修改人id',
  `modified_name` varchar(64) DEFAULT NULL COMMENT '最后修改人',
  `yn` tinyint(4) DEFAULT '1' COMMENT '逻辑标记,1:有效;-1:无效',
  `approve_message` varchar(256) DEFAULT NULL COMMENT '审批原因',
  `tenant_enabled` tinyint(4) DEFAULT '0' COMMENT '是否租户自动启用插件,1:租户自动启用;0:非租户自动启用;默认:0',
  `page_zip_url` varchar(500) DEFAULT NULL COMMENT '页面压缩包地址',
  `target_sub_type` varchar(32) DEFAULT NULL COMMENT '子类型'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生态市场配置';

-- --------------------------------------------------------

--
-- 表的结构 `eco_market_client_publish_config`
--

CREATE TABLE `eco_market_client_publish_config` (
  `id` bigint(20) NOT NULL COMMENT '主键id',
  `uid` varchar(128) NOT NULL COMMENT '唯一ID,分布式唯一UUID',
  `name` varchar(128) NOT NULL COMMENT '名称',
  `description` varchar(256) DEFAULT NULL COMMENT '描述',
  `data_type` tinyint(4) NOT NULL DEFAULT '1' COMMENT '市场类型,默认插件,1:插件;2:模板;3:MCP',
  `target_type` varchar(64) DEFAULT NULL COMMENT '细分类型,比如: 插件,智能体,工作流',
  `target_id` bigint(20) DEFAULT NULL COMMENT '具体目标的id,可以智能体,工作流,插件,还有mcp等',
  `category_code` varchar(128) DEFAULT NULL COMMENT '分类编码,商业服务等,通过接口获取',
  `category_name` varchar(128) DEFAULT NULL COMMENT '分类名称,商业服务等,通过接口获取',
  `owned_flag` tinyint(4) NOT NULL DEFAULT '0' COMMENT '是否我的分享,0:否(生态市场获取的);1:是(我的分享)',
  `share_status` tinyint(4) NOT NULL DEFAULT '1' COMMENT '分享状态,1:草稿;2:审核中;3:已发布;4:已下线;5:驳回',
  `use_status` tinyint(4) NOT NULL DEFAULT '1' COMMENT '使用状态,1:启用;2:禁用;',
  `publish_time` datetime DEFAULT NULL COMMENT '发布时间',
  `offline_time` datetime DEFAULT NULL COMMENT '下线时间',
  `version_number` bigint(20) NOT NULL DEFAULT '1' COMMENT '版本号,自增,发布一次增加1,初始值为1',
  `author` varchar(256) DEFAULT NULL COMMENT '作者信息',
  `publish_doc` mediumtext COMMENT '发布文档',
  `config_param_json` json DEFAULT NULL COMMENT '请求参数配置json',
  `config_json` json DEFAULT NULL COMMENT '配置json,存储插件的配置信息如果有其他额外的信息保存放这里',
  `icon` varchar(255) DEFAULT NULL COMMENT '图标图片地址',
  `_tenant_id` bigint(20) NOT NULL COMMENT '租户ID',
  `create_client_id` varchar(128) NOT NULL COMMENT '创建者的客户端ID',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `creator_id` bigint(20) DEFAULT NULL COMMENT '创建人id',
  `creator_name` varchar(64) DEFAULT NULL COMMENT '创建人',
  `modified` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `modified_id` bigint(20) DEFAULT NULL COMMENT '最后修改人id',
  `modified_name` varchar(64) DEFAULT NULL COMMENT '最后修改人',
  `yn` tinyint(4) DEFAULT '1' COMMENT '逻辑标记,1:有效;-1:无效',
  `approve_message` varchar(256) DEFAULT NULL COMMENT '审批原因',
  `tenant_enabled` tinyint(4) DEFAULT '0' COMMENT '是否租户自动启用插件,1:租户自动启用;0:非租户自动启用;默认:0',
  `page_zip_url` varchar(500) DEFAULT NULL COMMENT '页面压缩包地址',
  `target_sub_type` varchar(32) DEFAULT NULL COMMENT '子类型'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生态市场,客户端,已发布配置';

-- --------------------------------------------------------

--
-- 表的结构 `eco_market_client_secret`
--

CREATE TABLE `eco_market_client_secret` (
  `id` bigint(20) NOT NULL COMMENT '主键id',
  `name` varchar(128) NOT NULL COMMENT '名称',
  `description` varchar(256) DEFAULT NULL COMMENT '描述',
  `client_id` varchar(128) NOT NULL COMMENT '客户端ID,分布式唯一UUID',
  `client_secret` varchar(256) DEFAULT NULL COMMENT '客户端密钥',
  `_tenant_id` bigint(20) NOT NULL COMMENT '租户ID',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `creator_id` bigint(20) DEFAULT NULL COMMENT '创建人id',
  `creator_name` varchar(64) DEFAULT NULL COMMENT '创建人',
  `modified` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `yn` tinyint(4) DEFAULT '1' COMMENT '逻辑标记,1:有效;-1:无效'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生态市场,客户端端配置';

-- --------------------------------------------------------

--
-- 表的结构 `knowledge_config`
--

CREATE TABLE `knowledge_config` (
  `id` bigint(20) NOT NULL COMMENT '主键id',
  `name` varchar(32) NOT NULL COMMENT '知识库名称',
  `description` varchar(256) DEFAULT NULL COMMENT '知识库描述',
  `pub_status` enum('Waiting','Published') NOT NULL DEFAULT 'Waiting',
  `data_type` tinyint(4) NOT NULL DEFAULT '1' COMMENT '数据类型,默认文本,1:文本;2:表格',
  `embedding_model_id` int(11) DEFAULT NULL COMMENT '知识库的嵌入模型ID',
  `chat_model_id` int(11) DEFAULT NULL COMMENT '知识库的生成Q&A模型ID',
  `_tenant_id` bigint(20) NOT NULL COMMENT '租户ID',
  `space_id` bigint(20) NOT NULL COMMENT '所属空间ID',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `creator_id` bigint(20) DEFAULT NULL COMMENT '创建人id',
  `creator_name` varchar(64) DEFAULT NULL COMMENT '创建人',
  `modified` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `modified_id` bigint(20) DEFAULT NULL COMMENT '最后修改人id',
  `modified_name` varchar(64) DEFAULT NULL COMMENT '最后修改人',
  `yn` tinyint(4) DEFAULT '1' COMMENT '逻辑标记,1:有效;-1:无效',
  `icon` varchar(255) DEFAULT NULL COMMENT '图标图片地址',
  `file_size` bigint(20) DEFAULT '0' COMMENT '文件大小,单位字节byte',
  `workflow_id` bigint(20) DEFAULT NULL COMMENT '工作流id,可选,已工作流的形式,来执行解析文档获取文本的任务',
  `fulltext_sync_status` tinyint(4) DEFAULT '0' COMMENT '全文检索同步状态: 0-未同步, 1-同步中, 2-已同步, -1-同步失败',
  `fulltext_sync_time` datetime DEFAULT NULL COMMENT '全文检索最后同步时间',
  `fulltext_segment_count` bigint(20) DEFAULT '0' COMMENT '已同步到全文检索的分段数量'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库表';

-- --------------------------------------------------------

--
-- 表的结构 `knowledge_document`
--

CREATE TABLE `knowledge_document` (
  `id` bigint(20) NOT NULL COMMENT '主键id',
  `kb_id` bigint(20) NOT NULL COMMENT '文档所属知识库',
  `name` varchar(128) NOT NULL COMMENT '文档名称',
  `doc_url` varchar(256) NOT NULL COMMENT '文件URL',
  `pub_status` enum('Waiting','Published') NOT NULL,
  `has_qa` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否已经生成Q&A',
  `has_embedding` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否已经完成嵌入',
  `segment` json DEFAULT NULL COMMENT '文档分段方式（需要记录分段方式，基于字符数量或换行，Q&A字段等）。如果为空，表示还没有进行分段',
  `_tenant_id` bigint(20) NOT NULL COMMENT '租户ID',
  `space_id` bigint(20) NOT NULL COMMENT '所属空间ID',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `creator_id` bigint(20) DEFAULT NULL COMMENT '创建人id',
  `creator_name` varchar(64) DEFAULT NULL COMMENT '创建人',
  `modified` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `modified_id` bigint(20) DEFAULT NULL COMMENT '最后修改人id',
  `modified_name` varchar(64) DEFAULT NULL COMMENT '最后修改人',
  `yn` tinyint(4) DEFAULT '1' COMMENT '逻辑标记,1:有效;-1:无效',
  `file_content` longtext COMMENT '自定义文本内容,自定义添加会有',
  `data_type` tinyint(4) NOT NULL DEFAULT '1' COMMENT '文件类型,1:URL访问文件;2:自定义文本内容',
  `file_size` bigint(20) DEFAULT '0' COMMENT '件大小,单位字节byte'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库-原始文档表';

-- --------------------------------------------------------

--
-- 表的结构 `knowledge_qa_segment`
--

CREATE TABLE `knowledge_qa_segment` (
  `id` bigint(20) NOT NULL COMMENT '主键id',
  `doc_id` bigint(20) NOT NULL COMMENT '分段所属文档',
  `raw_id` bigint(20) DEFAULT NULL COMMENT '所属原始分段ID,前端手动新增的没有归属分段内容',
  `question` text COMMENT '问题会进行嵌入（对分段的增删改会走大模型并调用向量数据库）',
  `answer` text COMMENT '答案会进行嵌入（对分段的增删改会走大模型并调用向量数据库）',
  `kb_id` bigint(20) NOT NULL COMMENT '知识库ID',
  `has_embedding` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否已经完成嵌入',
  `_tenant_id` bigint(20) NOT NULL COMMENT '租户ID',
  `space_id` bigint(20) NOT NULL COMMENT '所属空间ID',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `creator_id` bigint(20) DEFAULT NULL COMMENT '创建人id',
  `creator_name` varchar(64) DEFAULT NULL COMMENT '创建人',
  `modified` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `modified_id` bigint(20) DEFAULT NULL COMMENT '最后修改人id',
  `modified_name` varchar(64) DEFAULT NULL COMMENT '最后修改人',
  `yn` tinyint(4) DEFAULT '1' COMMENT '逻辑标记,1:有效;-1:无效'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='问答表';

-- --------------------------------------------------------

--
-- 表的结构 `knowledge_raw_segment`
--

CREATE TABLE `knowledge_raw_segment` (
  `id` bigint(20) NOT NULL COMMENT '主键id',
  `doc_id` bigint(20) NOT NULL COMMENT '分段所属文档',
  `raw_txt` mediumtext COMMENT '原始文本',
  `kb_id` bigint(20) NOT NULL COMMENT '知识库ID',
  `sort_index` int(11) NOT NULL COMMENT '排序索引,在归属同一个文档下，段的排序',
  `_tenant_id` bigint(20) NOT NULL COMMENT '租户ID',
  `space_id` bigint(20) NOT NULL COMMENT '所属空间ID',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `creator_id` bigint(20) DEFAULT NULL COMMENT '创建人id',
  `creator_name` varchar(64) DEFAULT NULL COMMENT '创建人',
  `modified` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `modified_id` bigint(20) DEFAULT NULL COMMENT '最后修改人id',
  `modified_name` varchar(64) DEFAULT NULL COMMENT '最后修改人',
  `yn` tinyint(4) DEFAULT '1' COMMENT '逻辑标记,1:有效;-1:无效',
  `qa_status` tinyint(4) DEFAULT '-1' COMMENT '-1:待生成问答;1:已生成问答;',
  `fulltext_sync_status` tinyint(4) DEFAULT '0' COMMENT '全文检索同步状态: 0-未同步, 1-已同步',
  `fulltext_sync_time` datetime DEFAULT NULL COMMENT '全文检索同步时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='原始分段（也称chunk）表，这些信息待生成问答后可以不再保存';

-- --------------------------------------------------------

--
-- 表的结构 `knowledge_task`
--

CREATE TABLE `knowledge_task` (
  `id` bigint(20) NOT NULL COMMENT '主键id',
  `kb_id` bigint(20) NOT NULL COMMENT '文档所属知识库',
  `space_id` bigint(20) NOT NULL COMMENT '所属空间ID',
  `doc_id` bigint(20) NOT NULL COMMENT '文档id',
  `type` tinyint(4) NOT NULL COMMENT '任务重试阶段类型:1:文档分段;2:生成Q&A;3:生成嵌入;10:任务完毕',
  `tid` varchar(100) NOT NULL COMMENT 'tid',
  `name` varchar(128) NOT NULL COMMENT '任务名称',
  `status` tinyint(4) NOT NULL COMMENT '状态，0:初始状态,1待重试，2重试成功，3重试失败，4禁止重试',
  `max_retry_cnt` int(11) NOT NULL DEFAULT '5' COMMENT '最大重试次数',
  `retry_cnt` int(11) NOT NULL DEFAULT '0' COMMENT '已重试次数',
  `result` mediumtext COMMENT '调用结果',
  `_tenant_id` bigint(20) NOT NULL COMMENT '租户ID',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `creator_id` bigint(20) DEFAULT NULL COMMENT '创建人id',
  `creator_name` varchar(64) DEFAULT NULL COMMENT '创建人',
  `modified` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `yn` tinyint(4) DEFAULT '1' COMMENT '逻辑标记,1:有效;-1:无效'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库-定时任务';

-- --------------------------------------------------------

--
-- 表的结构 `knowledge_task_history`
--

CREATE TABLE `knowledge_task_history` (
  `id` bigint(20) NOT NULL COMMENT '主键id',
  `kb_id` bigint(20) NOT NULL COMMENT '文档所属知识库',
  `space_id` bigint(20) NOT NULL COMMENT '所属空间ID',
  `doc_id` bigint(20) NOT NULL COMMENT '文档id',
  `type` tinyint(4) NOT NULL COMMENT '任务重试阶段类型:1:文档分段;2:生成Q&A;3:生成嵌入;10:任务完毕',
  `tid` varchar(100) NOT NULL COMMENT 'tid',
  `name` varchar(128) NOT NULL COMMENT '任务名称',
  `status` tinyint(4) NOT NULL COMMENT '状态，0:初始状态,1待重试，2重试成功，3重试失败，4禁止重试',
  `max_retry_cnt` int(11) NOT NULL DEFAULT '5' COMMENT '最大重试次数',
  `retry_cnt` int(11) NOT NULL DEFAULT '0' COMMENT '已重试次数',
  `result` mediumtext COMMENT '调用结果',
  `_tenant_id` bigint(20) NOT NULL COMMENT '租户ID',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `creator_id` bigint(20) DEFAULT NULL COMMENT '创建人id',
  `creator_name` varchar(64) DEFAULT NULL COMMENT '创建人',
  `modified` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `yn` tinyint(4) DEFAULT '1' COMMENT '逻辑标记,1:有效;-1:无效'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库-定时任务-历史';

-- --------------------------------------------------------

--
-- 表的结构 `mcp_config`
--

CREATE TABLE `mcp_config` (
  `id` bigint(20) NOT NULL,
  `_tenant_id` bigint(20) NOT NULL DEFAULT '1' COMMENT '租户ID',
  `space_id` bigint(20) NOT NULL COMMENT '空间ID',
  `creator_id` bigint(20) NOT NULL COMMENT '创建用户ID',
  `uid` varchar(64) DEFAULT NULL,
  `name` varchar(64) NOT NULL COMMENT 'MCP名称',
  `server_name` varchar(64) DEFAULT NULL,
  `description` text COMMENT 'MCP描述信息',
  `icon` varchar(255) DEFAULT NULL COMMENT 'icon图片地址',
  `category` varchar(64) DEFAULT NULL,
  `install_type` varchar(64) NOT NULL COMMENT 'MCP安装类型',
  `deploy_status` varchar(64) NOT NULL DEFAULT 'Initialization' COMMENT '部署状态',
  `config` json DEFAULT NULL COMMENT 'MCP配置',
  `deployed_config` json DEFAULT NULL COMMENT 'MCP已发布的配置',
  `deployed` datetime DEFAULT NULL COMMENT '部署时间',
  `modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `memory_unit`
--

CREATE TABLE `memory_unit` (
  `id` bigint(20) NOT NULL COMMENT '主键ID',
  `_tenant_id` bigint(20) NOT NULL COMMENT '租户ID',
  `user_id` bigint(20) NOT NULL COMMENT '用户ID',
  `agent_id` bigint(20) DEFAULT NULL COMMENT '代理ID',
  `category` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '一级分类',
  `sub_category` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '二级分类',
  `content_json` json DEFAULT NULL COMMENT '内容JSON',
  `is_sensitive` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否敏感信息(0:否 1:是)',
  `status` enum('active','archived','deleted') COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'active' COMMENT '状态',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `modified` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='记忆单元表';

-- --------------------------------------------------------

--
-- 表的结构 `memory_unit_tag`
--

CREATE TABLE `memory_unit_tag` (
  `id` bigint(20) NOT NULL COMMENT '主键ID',
  `_tenant_id` bigint(20) NOT NULL COMMENT '租户ID',
  `user_id` bigint(20) NOT NULL COMMENT '用户ID',
  `memory_id` bigint(20) NOT NULL COMMENT '记忆ID',
  `tag_name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '标签名称',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `modified` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='记忆单元标签表';

-- --------------------------------------------------------

--
-- 表的结构 `model_config`
--

CREATE TABLE `model_config` (
  `id` bigint(20) NOT NULL,
  `_tenant_id` bigint(20) NOT NULL DEFAULT '1' COMMENT '商户ID',
  `space_id` bigint(20) DEFAULT NULL COMMENT '空间ID',
  `creator_id` bigint(20) NOT NULL COMMENT '创建者ID',
  `scope` enum('Space','Tenant','Global') NOT NULL DEFAULT 'Tenant' COMMENT '模型生效范围',
  `name` varchar(255) NOT NULL COMMENT '模型名称',
  `description` text COMMENT '模型描述',
  `model` varchar(128) DEFAULT NULL COMMENT '模型标识',
  `type` varchar(64) NOT NULL COMMENT '模型类型',
  `is_reason_model` int(4) NOT NULL DEFAULT '0',
  `network_type` enum('Internet','Intranet') NOT NULL DEFAULT 'Internet' COMMENT '联网类型',
  `nat_info` json DEFAULT NULL COMMENT '网络配置信息（内网模式使用）',
  `function_call` enum('Unsupported','CallSupported','StreamCallSupported') NOT NULL DEFAULT 'CallSupported' COMMENT '函数调用支持程度',
  `max_tokens` int(10) NOT NULL DEFAULT '8192' COMMENT '请求token上限',
  `max_context_tokens` int(10) NOT NULL DEFAULT '128000' COMMENT '模型支持最大上下文',
  `api_protocol` varchar(64) DEFAULT NULL COMMENT '模型接口协议',
  `api_info` json NOT NULL COMMENT 'API列表 [{"url":"","key":"","weight":1}]',
  `strategy` enum('RoundRobin','WeightedRoundRobin','LeastConnections','WeightedLeastConnections','Random','ResponseTime') NOT NULL DEFAULT 'WeightedRoundRobin' COMMENT '接口调用策略',
  `dimension` int(10) NOT NULL DEFAULT '1536' COMMENT '向量维度',
  `modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `enabled` tinyint(4) DEFAULT NULL COMMENT '启用状态',
  `access_control` tinyint(4) NOT NULL DEFAULT '0' COMMENT '是否管控'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `notify_message`
--

CREATE TABLE `notify_message` (
  `id` bigint(20) NOT NULL,
  `_tenant_id` bigint(20) NOT NULL DEFAULT '1' COMMENT '商户ID',
  `sender_id` bigint(20) DEFAULT NULL COMMENT '发送用户',
  `scope` varchar(32) NOT NULL COMMENT '消息范围 Broadcast 广播消息；Private 私对私消息',
  `content` mediumtext NOT NULL COMMENT '消息内容',
  `modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `notify_message_user`
--

CREATE TABLE `notify_message_user` (
  `id` bigint(20) NOT NULL,
  `_tenant_id` bigint(20) NOT NULL DEFAULT '1' COMMENT '商户ID',
  `notify_id` bigint(20) DEFAULT NULL COMMENT '通知消息ID',
  `user_id` bigint(20) DEFAULT NULL COMMENT '接收用户，广播消息user_id=-1',
  `read_status` enum('Read','Unread') NOT NULL DEFAULT 'Unread' COMMENT '已读状态',
  `modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `pf_retry_data`
--

CREATE TABLE `pf_retry_data` (
  `id` bigint(20) NOT NULL,
  `project_code` varchar(50) NOT NULL COMMENT '应用code',
  `app_code` varchar(50) NOT NULL COMMENT '模块code',
  `bean_name` varchar(200) NOT NULL COMMENT '服务接口',
  `method_name` varchar(100) NOT NULL COMMENT '接口方法',
  `tid` varchar(100) NOT NULL COMMENT 'tid',
  `status` tinyint(4) NOT NULL DEFAULT '1' COMMENT '状态，1待重试，2重试成功，3重试失败，4禁止重试',
  `max_retry_cnt` int(11) NOT NULL DEFAULT '5' COMMENT '最大重试次数',
  `retry_cnt` int(11) NOT NULL DEFAULT '0' COMMENT '已重试次数',
  `arg_class_names` varchar(600) DEFAULT NULL COMMENT '参数类型名称组',
  `arg_str` mediumtext COMMENT '参数数组JSONString格式，可编辑',
  `result` mediumtext COMMENT '调用结果',
  `creator_id` bigint(20) DEFAULT NULL COMMENT '操作人ID',
  `creator_name` varchar(100) DEFAULT NULL COMMENT '操作人名称',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `modified` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `modifier_id` bigint(20) DEFAULT NULL COMMENT '编辑人ID',
  `modifier_name` varchar(100) DEFAULT NULL COMMENT '编辑人名称',
  `lock_time` datetime DEFAULT NULL COMMENT '锁定至时间',
  `yn` tinyint(4) NOT NULL DEFAULT '1' COMMENT '是否有效',
  `ext` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin COMMENT '扩展信息',
  `_tenant_id` bigint(20) NOT NULL DEFAULT '-1'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='重试上报数据';

-- --------------------------------------------------------

--
-- 表的结构 `plugin_config`
--

CREATE TABLE `plugin_config` (
  `id` bigint(20) NOT NULL,
  `_tenant_id` bigint(20) NOT NULL DEFAULT '1' COMMENT '租户ID',
  `space_id` bigint(20) NOT NULL COMMENT '空间ID',
  `creator_id` bigint(20) NOT NULL COMMENT '创建用户ID',
  `name` varchar(64) NOT NULL COMMENT '插件名称',
  `description` text COMMENT '插件描述信息',
  `icon` varchar(255) DEFAULT NULL COMMENT 'icon图片地址',
  `type` varchar(64) NOT NULL COMMENT '插件类型',
  `code_lang` varchar(64) DEFAULT NULL COMMENT '插件类型为代码时，该字段填写代码语言js、python',
  `publish_status` enum('Developing','Applying','Published') NOT NULL DEFAULT 'Developing' COMMENT '发布状态',
  `config` json DEFAULT NULL COMMENT '插件配置',
  `modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `published`
--

CREATE TABLE `published` (
  `id` bigint(20) NOT NULL,
  `_tenant_id` bigint(20) NOT NULL,
  `space_id` bigint(20) DEFAULT NULL COMMENT '空间ID',
  `user_id` bigint(20) DEFAULT NULL COMMENT '发布者ID',
  `target_id` bigint(20) NOT NULL COMMENT '发布目标对象ID',
  `target_type` enum('Agent','Plugin','Workflow','Skill') NOT NULL COMMENT '发布类型',
  `target_sub_type` varchar(32) NOT NULL DEFAULT 'Single',
  `name` varchar(64) NOT NULL COMMENT '发布名称',
  `description` text COMMENT '描述信息',
  `icon` varchar(255) DEFAULT NULL COMMENT '图标',
  `remark` text COMMENT '发布记录',
  `config` json NOT NULL COMMENT '发布配置',
  `channel` varchar(32) NOT NULL DEFAULT 'Square' COMMENT '发布渠道：Square 广场；System 系统发布',
  `scope` enum('Tenant','Global','Space') NOT NULL DEFAULT 'Tenant' COMMENT '发布范围',
  `category` varchar(64) DEFAULT 'Other' COMMENT '分类',
  `allow_copy` tinyint(4) NOT NULL DEFAULT '0' COMMENT '是否允许复制',
  `access_control` tinyint(4) NOT NULL DEFAULT '0' COMMENT '是否受权限管控，0 不受管控；1 受控',
  `only_template` tinyint(4) NOT NULL DEFAULT '0' COMMENT '仅展示模板',
  `modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发布';

-- --------------------------------------------------------

--
-- 表的结构 `published_statistics`
--

CREATE TABLE `published_statistics` (
  `id` bigint(20) NOT NULL,
  `_tenant_id` bigint(20) NOT NULL DEFAULT '-1' COMMENT '商户ID',
  `target_id` bigint(20) NOT NULL COMMENT '目标统计对象ID',
  `target_type` enum('Agent','Plugin','Workflow','Skill') NOT NULL COMMENT '目标对象类型',
  `name` varchar(32) NOT NULL COMMENT '统计名称',
  `value` bigint(20) NOT NULL DEFAULT '0' COMMENT '统计值',
  `modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `publish_apply`
--

CREATE TABLE `publish_apply` (
  `id` bigint(20) NOT NULL,
  `_tenant_id` bigint(20) NOT NULL,
  `space_id` bigint(20) DEFAULT NULL COMMENT '空间ID',
  `apply_user_id` bigint(20) NOT NULL COMMENT '申请用户ID',
  `target_type` enum('Agent','Plugin','Workflow','Skill') NOT NULL COMMENT '审核目标类型',
  `target_id` bigint(20) NOT NULL,
  `name` varchar(64) NOT NULL COMMENT '发布名称',
  `description` text COMMENT '描述信息',
  `icon` varchar(255) DEFAULT NULL COMMENT '图标',
  `remark` text COMMENT '发布记录',
  `config` json NOT NULL COMMENT '发布配置',
  `channel` json DEFAULT NULL COMMENT '发布渠道：Square 广场；System 系统发布',
  `scope` enum('Space','Tenant','Global') DEFAULT 'Tenant' COMMENT '发布范围',
  `publish_status` varchar(32) NOT NULL COMMENT '发布审核状态',
  `category` varchar(64) NOT NULL DEFAULT '' COMMENT '分类',
  `allow_copy` tinyint(4) NOT NULL DEFAULT '0' COMMENT '是否允许复制',
  `only_template` tinyint(4) NOT NULL DEFAULT '0' COMMENT '仅展示模板',
  `modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `target_sub_type` varchar(32) DEFAULT NULL COMMENT '子类型'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `sandbox_config`
--

CREATE TABLE `sandbox_config` (
  `id` bigint(20) NOT NULL COMMENT '主键ID',
  `_tenant_id` bigint(20) NOT NULL COMMENT '租户id',
  `scope` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '配置范围：global-全局配置 user-个人配置',
  `user_id` bigint(20) DEFAULT NULL COMMENT '用户ID（scope为user时必填）',
  `agent_id` bigint(20) DEFAULT NULL COMMENT '智能体电脑绑定的agentId',
  `name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '配置名称（用于界面显示）',
  `config_key` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '唯一标识',
  `config_value` json NOT NULL COMMENT '配置值（JSON格式存储）',
  `server_info` json DEFAULT NULL COMMENT '内部访问信息',
  `description` text COMMENT '描述信息',
  `is_active` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用：1-启用 0-禁用',
  `max_agent` int(11) NOT NULL DEFAULT '5' COMMENT '最大可以开多少个agent并行执行（个人客户端有效）',
  `created` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `modified` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- --------------------------------------------------------

--
-- 表的结构 `sandbox_proxy`
--

CREATE TABLE `sandbox_proxy` (
  `id` bigint(20) NOT NULL,
  `_tenant_id` bigint(20) NOT NULL DEFAULT '1' COMMENT '租户ID',
  `user_id` bigint(20) NOT NULL COMMENT '用户ID',
  `sandbox_id` bigint(20) NOT NULL COMMENT '沙盒ID',
  `proxy_key` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '代理键',
  `backend_host` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '后端主机地址',
  `backend_port` int(11) NOT NULL COMMENT '后端端口',
  `modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='临时代理配置表';

-- --------------------------------------------------------

--
-- 表的结构 `schedule_task`
--

CREATE TABLE `schedule_task` (
  `id` bigint(20) NOT NULL,
  `_tenant_id` bigint(20) NOT NULL DEFAULT '-1' COMMENT '租户ID',
  `space_id` bigint(20) NOT NULL DEFAULT '-1' COMMENT '空间ID',
  `creator_id` bigint(20) NOT NULL DEFAULT '-1' COMMENT '创建用户ID',
  `task_name` varchar(128) NOT NULL DEFAULT '' COMMENT '任务名称',
  `target_type` varchar(32) NOT NULL DEFAULT 'System' COMMENT '类型',
  `target_id` varchar(64) NOT NULL DEFAULT '-1' COMMENT '目标对象ID',
  `task_id` varchar(255) NOT NULL COMMENT '任务ID',
  `bean_id` varchar(128) NOT NULL COMMENT '回调处理器',
  `cron` varchar(32) NOT NULL COMMENT '执行周期',
  `params` json DEFAULT NULL COMMENT '附加参数',
  `status` varchar(32) NOT NULL COMMENT '调用状态',
  `lock_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '锁定时间',
  `latest_exec_time` datetime DEFAULT NULL COMMENT '最近一次执行时间',
  `exec_times` bigint(20) NOT NULL DEFAULT '0' COMMENT '已执行次数',
  `max_exec_times` bigint(20) NOT NULL COMMENT '最大执行次数',
  `error` text COMMENT '错误信息',
  `modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务调度表';

-- --------------------------------------------------------

--
-- 表的结构 `skill_config`
--

CREATE TABLE `skill_config` (
  `id` bigint(20) NOT NULL COMMENT '主键ID',
  `name` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '技能名称',
  `description` text COLLATE utf8mb4_unicode_ci COMMENT '技能描述',
  `icon` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '技能图标',
  `files` longtext COLLATE utf8mb4_unicode_ci COMMENT '内容',
  `publish_status` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'Developing' COMMENT '发布状态',
  `_tenant_id` bigint(20) NOT NULL COMMENT '租户ID',
  `space_id` bigint(20) DEFAULT NULL COMMENT '空间ID',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `creator_id` bigint(20) DEFAULT NULL COMMENT '创建人ID',
  `creator_name` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建人',
  `modified` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `modified_id` bigint(20) DEFAULT NULL COMMENT '最后修改人ID',
  `modified_name` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '最后修改人',
  `yn` tinyint(4) NOT NULL DEFAULT '1' COMMENT '逻辑标记,1:有效;-1:无效'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='技能配置';

-- --------------------------------------------------------

--
-- 表的结构 `space`
--

CREATE TABLE `space` (
  `id` bigint(20) NOT NULL,
  `_tenant_id` bigint(20) NOT NULL DEFAULT '1' COMMENT '商户ID',
  `name` varchar(32) NOT NULL COMMENT '空间名称',
  `description` text COMMENT '描述信息',
  `icon` text COMMENT '空间图标',
  `creator_id` bigint(20) NOT NULL COMMENT '创建者ID',
  `type` enum('Personal','Team','Class') NOT NULL DEFAULT 'Team' COMMENT '空间类型',
  `receive_publish` tinyint(4) NOT NULL DEFAULT '1' COMMENT '是否允许来自外部空间的发布',
  `allow_develop` tinyint(4) NOT NULL DEFAULT '1' COMMENT '是否开启开发者功能',
  `yn` tinyint(4) NOT NULL DEFAULT '0' COMMENT '逻辑删除，1为删除',
  `modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='团队空间';

-- --------------------------------------------------------

--
-- 表的结构 `space_user`
--

CREATE TABLE `space_user` (
  `id` bigint(20) NOT NULL,
  `_tenant_id` bigint(20) NOT NULL DEFAULT '1' COMMENT '商户ID',
  `space_id` bigint(20) NOT NULL COMMENT '空间ID',
  `user_id` bigint(20) NOT NULL COMMENT '人员ID',
  `role` enum('Owner','Admin','User') NOT NULL DEFAULT 'User' COMMENT '空间角色',
  `modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='空间人员';

-- --------------------------------------------------------

--
-- 表的结构 `system_config`
--

CREATE TABLE `system_config` (
  `config_id` int(11) NOT NULL,
  `name` varchar(32) NOT NULL,
  `value` text NOT NULL,
  `type` varchar(32) NOT NULL DEFAULT 'system',
  `input_type` varchar(16) DEFAULT NULL,
  `description` varchar(255) NOT NULL,
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统全局配置';

-- --------------------------------------------------------

--
-- 表的结构 `sys_data_permission`
--

CREATE TABLE `sys_data_permission` (
  `id` bigint(20) NOT NULL COMMENT '主键ID',
  `target_type` tinyint(4) NOT NULL COMMENT '目标类型，对应 PermissionTargetTypeEnum',
  `target_id` bigint(20) NOT NULL COMMENT '目标ID',
  `token_limit` json DEFAULT NULL COMMENT 'token 限制（JSON）',
  `max_space_count` int(11) DEFAULT NULL COMMENT '可创建工作空间数量，-1 表示不限制',
  `max_agent_count` int(11) DEFAULT NULL COMMENT '可创建智能体数量，-1 表示不限制',
  `max_page_app_count` int(11) DEFAULT NULL COMMENT '可创建网页应用数量，-1 表示不限制',
  `max_knowledge_count` int(11) DEFAULT NULL COMMENT '可创建知识库数量，-1 表示不限制',
  `knowledge_storage_limit_gb` decimal(10,3) DEFAULT NULL COMMENT '知识库存储空间上限(GB)，-1 表示不限制',
  `max_data_table_count` int(11) DEFAULT NULL COMMENT '可创建数据表数量，-1 表示不限制',
  `max_scheduled_task_count` int(11) DEFAULT NULL COMMENT '可创建定时任务数量，-1 表示不限制',
  `allow_api_external_call` tinyint(4) DEFAULT NULL COMMENT '是否允许API外部调用，1-允许，0-不允许',
  `agent_computer_cpu_cores` int(11) DEFAULT NULL COMMENT '智能体电脑CPU核心数',
  `agent_computer_memory_gb` int(11) DEFAULT NULL COMMENT '智能体电脑内存(GB)',
  `agent_computer_swap_gb` int(11) DEFAULT NULL COMMENT '智能体电脑交换分区(GB)',
  `agent_file_storage_days` int(11) DEFAULT NULL COMMENT '通用智能体执行结果文件存储天数(仅云端电脑受限)，-1 表示不限制',
  `agent_daily_prompt_limit` int(11) DEFAULT NULL COMMENT '通用智能体每天对话次数(含编排调试，问答智能体不限)，-1 表示不限制',
  `page_daily_prompt_limit` int(11) DEFAULT NULL COMMENT '页面应用每天对话次数，-1表示不限制',
  `_tenant_id` bigint(20) NOT NULL COMMENT '租户ID',
  `creator_id` bigint(20) DEFAULT NULL COMMENT '创建人ID',
  `creator` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建人',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `modifier_id` bigint(20) DEFAULT NULL COMMENT '修改人ID',
  `modifier` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '修改人',
  `modified` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `yn` tinyint(4) NOT NULL DEFAULT '1' COMMENT '是否有效；1：有效，-1：无效'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据权限';

-- --------------------------------------------------------

--
-- 表的结构 `sys_group`
--

CREATE TABLE `sys_group` (
  `id` bigint(20) NOT NULL COMMENT 'ID',
  `parent_id` bigint(20) DEFAULT NULL COMMENT '父节点 ID',
  `code` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '编码',
  `name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '名称',
  `description` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '描述',
  `max_user_count` int(11) DEFAULT NULL COMMENT '最大用户数',
  `source` tinyint(4) NOT NULL COMMENT '来源：1-系统内置，2-用户自定义，对应 SourceEnum',
  `status` tinyint(4) NOT NULL COMMENT '状态：1-启用，0-禁用',
  `sort_index` int(11) DEFAULT NULL COMMENT '排序',
  `_tenant_id` bigint(20) NOT NULL COMMENT '租户ID',
  `creator_id` bigint(20) DEFAULT NULL COMMENT '创建人ID',
  `creator` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建人',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `modifier_id` bigint(20) DEFAULT NULL COMMENT '修改人ID',
  `modifier` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '修改人',
  `modified` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `yn` tinyint(4) NOT NULL DEFAULT '1' COMMENT '是否有效；1：有效，-1：无效'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户组';

-- --------------------------------------------------------

--
-- 表的结构 `sys_group_menu`
--

CREATE TABLE `sys_group_menu` (
  `id` bigint(20) NOT NULL COMMENT '主键ID',
  `group_id` bigint(20) NOT NULL COMMENT '用户组ID',
  `menu_id` bigint(20) NOT NULL COMMENT '菜单ID',
  `menu_bind_type` tinyint(4) NOT NULL DEFAULT '0' COMMENT '子菜单绑定类型 0:未绑定 1:全部绑定 2:部分绑定',
  `resource_tree_json` json DEFAULT NULL COMMENT '资源树JSON（包含每个节点的绑定类型）',
  `_tenant_id` bigint(20) NOT NULL COMMENT '租户ID',
  `creator_id` bigint(20) DEFAULT NULL COMMENT '创建人ID',
  `creator` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建人',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `modifier_id` bigint(20) DEFAULT NULL COMMENT '修改人ID',
  `modifier` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '修改人',
  `modified` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `yn` tinyint(4) NOT NULL DEFAULT '1' COMMENT '是否有效；1：有效，-1：无效'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户组和菜单关联';

-- --------------------------------------------------------

--
-- 表的结构 `sys_menu`
--

CREATE TABLE `sys_menu` (
  `id` bigint(20) NOT NULL COMMENT '菜单ID',
  `parent_id` bigint(20) DEFAULT NULL COMMENT '父级ID',
  `code` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '资源码，唯一标识',
  `name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '菜单名称',
  `description` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '描述',
  `source` tinyint(4) NOT NULL COMMENT '来源，对应 SourceEnum',
  `path` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '访问路径/路由地址',
  `open_type` tinyint(4) DEFAULT '1' COMMENT '打开方式',
  `icon` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '图标',
  `sort_index` int(11) DEFAULT NULL COMMENT '排序',
  `status` tinyint(4) NOT NULL DEFAULT '1' COMMENT '状态：1-启用，0-禁用',
  `_tenant_id` bigint(20) NOT NULL COMMENT '租户ID',
  `creator_id` bigint(20) DEFAULT NULL COMMENT '创建人ID',
  `creator` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建人',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `modifier_id` bigint(20) DEFAULT NULL COMMENT '修改人ID',
  `modifier` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '修改人',
  `modified` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `yn` tinyint(4) NOT NULL DEFAULT '1' COMMENT '是否有效；1：有效，-1：无效'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='菜单';

-- --------------------------------------------------------

--
-- 表的结构 `sys_menu_resource`
--

CREATE TABLE `sys_menu_resource` (
  `id` bigint(20) NOT NULL COMMENT '主键ID',
  `menu_id` bigint(20) NOT NULL COMMENT '菜单ID',
  `resource_id` bigint(20) NOT NULL COMMENT '资源ID',
  `resource_bind_type` tinyint(4) NOT NULL DEFAULT '0' COMMENT '资源绑定类型 0:未绑定 1:全部绑定 2:部分绑定',
  `_tenant_id` bigint(20) NOT NULL COMMENT '租户ID',
  `creator_id` bigint(20) DEFAULT NULL COMMENT '创建人ID',
  `creator` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建人',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `modifier_id` bigint(20) DEFAULT NULL COMMENT '修改人ID',
  `modifier` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '修改人',
  `modified` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `yn` tinyint(4) NOT NULL DEFAULT '1' COMMENT '是否有效；1：有效，-1：无效'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='菜单资源关联';

-- --------------------------------------------------------

--
-- 表的结构 `sys_operator_log`
--

CREATE TABLE `sys_operator_log` (
  `id` bigint(20) NOT NULL COMMENT '自增主键',
  `operate_type` tinyint(4) NOT NULL COMMENT '1:操作类型;2:访问日志',
  `system_code` varchar(64) DEFAULT NULL COMMENT '系统编码',
  `system_name` varchar(64) NOT NULL COMMENT '系统名称',
  `object_op` varchar(64) NOT NULL COMMENT '操作对象,比如:用户表,角色表,菜单表',
  `action` varchar(64) NOT NULL COMMENT '操作动作,比如:新增,删除,修改,查看',
  `operate_content` varchar(256) DEFAULT NULL COMMENT '操作内容,比如评估页面',
  `extra_content` text COMMENT '额外的操作内容信息记录,比如:更新提交的数据内容',
  `org_id` bigint(20) NOT NULL COMMENT '操作人所属机构id',
  `org_name` varchar(256) NOT NULL COMMENT '操作人所属机构名称',
  `creator_id` bigint(20) NOT NULL COMMENT '创建人id',
  `creator` varchar(64) DEFAULT NULL COMMENT '创建人名称',
  `created` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `modified` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `_tenant_id` bigint(20) NOT NULL COMMENT '租户ID',
  `yn` tinyint(4) NOT NULL DEFAULT '1' COMMENT '是否有效；1：有效，-1：无效'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志';

-- --------------------------------------------------------

--
-- 表的结构 `sys_resource`
--

CREATE TABLE `sys_resource` (
  `id` bigint(20) NOT NULL COMMENT '资源ID',
  `parent_id` bigint(20) DEFAULT NULL COMMENT '父级ID',
  `code` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '编码',
  `name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '名称',
  `description` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '描述',
  `source` tinyint(4) NOT NULL COMMENT '来源，对应 SourceEnum',
  `type` tinyint(4) NOT NULL COMMENT '类型，对应 ResourceTypeEnum',
  `path` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '访问路径',
  `icon` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '图标',
  `sort_index` int(11) DEFAULT NULL COMMENT '排序',
  `status` tinyint(4) NOT NULL DEFAULT '1' COMMENT '状态：1-启用，0-禁用',
  `_tenant_id` bigint(20) NOT NULL COMMENT '租户ID',
  `creator_id` bigint(20) DEFAULT NULL COMMENT '创建人ID',
  `creator` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建人',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `modifier_id` bigint(20) DEFAULT NULL COMMENT '修改人ID',
  `modifier` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '修改人',
  `modified` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `yn` tinyint(4) NOT NULL DEFAULT '1' COMMENT '是否有效；1：有效，-1：无效'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='资源';

-- --------------------------------------------------------

--
-- 表的结构 `sys_role`
--

CREATE TABLE `sys_role` (
  `id` bigint(20) NOT NULL COMMENT '角色ID',
  `code` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '编码',
  `name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '名称',
  `description` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '描述',
  `source` tinyint(4) NOT NULL COMMENT '来源，对应 SourceEnum',
  `status` tinyint(4) NOT NULL COMMENT '状态：1-启用，0-禁用',
  `sort_index` int(11) DEFAULT NULL COMMENT '排序',
  `_tenant_id` bigint(20) NOT NULL COMMENT '租户ID',
  `creator_id` bigint(20) DEFAULT NULL COMMENT '创建人ID',
  `creator` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建人',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `modifier_id` bigint(20) DEFAULT NULL COMMENT '修改人ID',
  `modifier` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '修改人',
  `modified` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `yn` tinyint(4) NOT NULL DEFAULT '1' COMMENT '是否有效；1：有效，-1：无效'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色';

-- --------------------------------------------------------

--
-- 表的结构 `sys_role_menu`
--

CREATE TABLE `sys_role_menu` (
  `id` bigint(20) NOT NULL COMMENT '主键ID',
  `role_id` bigint(20) NOT NULL COMMENT '角色ID',
  `menu_id` bigint(20) NOT NULL COMMENT '菜单ID',
  `menu_bind_type` tinyint(4) NOT NULL DEFAULT '0' COMMENT '子菜单绑定类型 0:未绑定 1:全部绑定 2:部分绑定',
  `resource_tree_json` json DEFAULT NULL COMMENT '资源树JSON（包含每个节点的绑定类型）',
  `_tenant_id` bigint(20) NOT NULL COMMENT '租户ID',
  `creator_id` bigint(20) DEFAULT NULL COMMENT '创建人ID',
  `creator` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建人',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `modifier_id` bigint(20) DEFAULT NULL COMMENT '修改人ID',
  `modifier` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '修改人',
  `modified` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `yn` tinyint(4) NOT NULL DEFAULT '1' COMMENT '是否有效；1：有效，-1：无效'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色和菜单关联';

-- --------------------------------------------------------

--
-- 表的结构 `sys_subject_permission`
--

CREATE TABLE `sys_subject_permission` (
  `id` bigint(20) NOT NULL COMMENT '主键ID',
  `subject_type` tinyint(4) NOT NULL COMMENT '主体类型，对应 PermissionSubjectTypeEnum（1-通用智能体，2-应用页面）',
  `subject_id` bigint(20) NOT NULL COMMENT '主体ID（智能体ID/页面ID）',
  `target_type` tinyint(4) NOT NULL COMMENT '目标类型，对应 PermissionTargetTypeEnum（角色/用户组）',
  `target_id` bigint(20) NOT NULL COMMENT '目标ID（角色/用户组ID）',
  `_tenant_id` bigint(20) NOT NULL COMMENT '租户ID',
  `creator_id` bigint(20) DEFAULT NULL COMMENT '创建人ID',
  `creator` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建人',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `modifier_id` bigint(20) DEFAULT NULL COMMENT '修改人ID',
  `modifier` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '修改人',
  `modified` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `yn` tinyint(4) NOT NULL DEFAULT '1' COMMENT '是否有效；1：有效，-1：无效'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='主体访问权限';

-- --------------------------------------------------------

--
-- 表的结构 `sys_user_group`
--

CREATE TABLE `sys_user_group` (
  `id` bigint(20) NOT NULL COMMENT '主键ID',
  `user_id` bigint(20) NOT NULL COMMENT '用户ID',
  `group_id` bigint(20) NOT NULL COMMENT '组ID',
  `_tenant_id` bigint(20) NOT NULL COMMENT '租户ID',
  `creator_id` bigint(20) DEFAULT NULL COMMENT '创建人ID',
  `creator` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建人',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `modifier_id` bigint(20) DEFAULT NULL COMMENT '修改人ID',
  `modifier` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '修改人',
  `modified` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `yn` tinyint(4) NOT NULL DEFAULT '1' COMMENT '是否有效；1：有效，-1：无效'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户和组关联';

-- --------------------------------------------------------

--
-- 表的结构 `sys_user_role`
--

CREATE TABLE `sys_user_role` (
  `id` bigint(20) NOT NULL COMMENT '主键ID',
  `user_id` bigint(20) NOT NULL COMMENT '用户ID',
  `role_id` bigint(20) NOT NULL COMMENT '角色ID',
  `_tenant_id` bigint(20) NOT NULL COMMENT '租户ID',
  `creator_id` bigint(20) DEFAULT NULL COMMENT '创建人ID',
  `creator` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建人',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `modifier_id` bigint(20) DEFAULT NULL COMMENT '修改人ID',
  `modifier` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '修改人',
  `modified` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `yn` tinyint(4) NOT NULL DEFAULT '1' COMMENT '是否有效；1：有效，-1：无效'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户和角色关联';

-- --------------------------------------------------------

--
-- 表的结构 `tenant`
--

CREATE TABLE `tenant` (
  `id` bigint(20) NOT NULL,
  `name` varchar(255) NOT NULL COMMENT '商户名称',
  `description` text COMMENT '商户介绍',
  `status` enum('Pending','Enabled','Disabled') NOT NULL COMMENT '商户状态',
  `domain` varchar(64) NOT NULL DEFAULT '',
  `version` varchar(64) NOT NULL DEFAULT '1.0.1',
  `modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `tenant_config`
--

CREATE TABLE `tenant_config` (
  `id` bigint(20) NOT NULL,
  `_tenant_id` bigint(20) NOT NULL,
  `description` varchar(255) NOT NULL,
  `name` varchar(32) NOT NULL,
  `value` json NOT NULL,
  `category` varchar(32) DEFAULT 'Base',
  `input_type` varchar(16) DEFAULT 'Input',
  `data_type` varchar(16) DEFAULT 'String',
  `notice` varchar(255) NOT NULL,
  `placeholder` varchar(255) NOT NULL,
  `min_height` int(50) DEFAULT NULL,
  `required` varchar(8) NOT NULL DEFAULT 'true',
  `sort` int(10) NOT NULL DEFAULT '0',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `tool`
--

CREATE TABLE `tool` (
  `tool_id` bigint(20) NOT NULL,
  `tool_key` varchar(32) NOT NULL COMMENT '工具唯一标识',
  `name` varchar(64) NOT NULL COMMENT '工具名称',
  `icon_url` varchar(255) DEFAULT NULL COMMENT '图标地址',
  `description` text COMMENT '工具描述',
  `handler_clazz` varchar(255) DEFAULT NULL COMMENT '处理类',
  `dto_clazz` varchar(255) NOT NULL DEFAULT '' COMMENT 'DTO类',
  `modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `user`
--

CREATE TABLE `user` (
  `id` bigint(20) NOT NULL COMMENT '平台用户ID',
  `_tenant_id` bigint(20) NOT NULL DEFAULT '1' COMMENT '商户ID',
  `uid` varchar(128) DEFAULT NULL COMMENT '用户唯一标识',
  `user_name` varchar(64) DEFAULT NULL COMMENT '用户姓名',
  `nick_name` varchar(64) DEFAULT NULL COMMENT '用户昵称',
  `avatar` varchar(255) DEFAULT NULL COMMENT '用户头像',
  `status` enum('Enabled','Disabled') NOT NULL DEFAULT 'Enabled' COMMENT '状态，启用或禁用',
  `role` enum('Admin','User') DEFAULT 'User' COMMENT '角色',
  `password` varchar(255) NOT NULL COMMENT '管理员密码',
  `reset_pass` tinyint(4) NOT NULL DEFAULT '0' COMMENT '是否设置过密码',
  `email` varchar(255) DEFAULT NULL COMMENT '管理员邮箱',
  `phone` varchar(64) DEFAULT NULL COMMENT '电话号码',
  `last_login_time` datetime DEFAULT NULL COMMENT '最后登录时间',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `modified` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- --------------------------------------------------------

--
-- 表的结构 `user_access_key`
--

CREATE TABLE `user_access_key` (
  `id` bigint(20) NOT NULL,
  `_tenant_id` bigint(20) NOT NULL COMMENT '租户ID',
  `user_id` bigint(20) NOT NULL COMMENT '用户ID',
  `target_type` varchar(64) NOT NULL COMMENT '目标业务类型',
  `target_id` varchar(64) DEFAULT NULL COMMENT '目标业务ID',
  `access_key` varchar(255) DEFAULT NULL COMMENT '访问密钥',
  `config` json DEFAULT NULL COMMENT '其他配置',
  `modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `user_agent_sort`
--

CREATE TABLE `user_agent_sort` (
  `id` bigint(20) NOT NULL,
  `_tenant_id` bigint(20) NOT NULL,
  `user_id` bigint(20) NOT NULL COMMENT '用户ID',
  `category` varchar(64) NOT NULL COMMENT '排序分类',
  `sort` int(10) NOT NULL COMMENT '分类排序',
  `agent_sort_config` json DEFAULT NULL COMMENT '分类下智能体排序配置',
  `modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `user_metric`
--

CREATE TABLE `user_metric` (
  `id` bigint(20) NOT NULL,
  `_tenant_id` bigint(20) DEFAULT NULL,
  `user_id` bigint(20) NOT NULL COMMENT '用户ID',
  `biz_type` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '业务类型',
  `period_type` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '时段类型: YEAR, MONTH, DAY, HOUR',
  `period` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '时段值: 2026, 202601, 20260129, 2026012912',
  `value` decimal(20,2) NOT NULL DEFAULT '0.00' COMMENT '计量值',
  `modified` datetime DEFAULT NULL,
  `created` datetime DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- --------------------------------------------------------

--
-- 表的结构 `user_req`
--

CREATE TABLE `user_req` (
  `id` bigint(20) NOT NULL COMMENT '主键ID',
  `_tenant_id` bigint(20) NOT NULL DEFAULT '1' COMMENT '商户ID',
  `user_id` bigint(20) NOT NULL COMMENT '用户ID',
  `dt` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '日期 YYYYMMDD',
  `req_count` int(11) NOT NULL DEFAULT '1' COMMENT '请求次数',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `modified` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户每日请求统计表';

-- --------------------------------------------------------

--
-- 表的结构 `user_request`
--

CREATE TABLE `user_request` (
  `id` bigint(20) NOT NULL COMMENT 'ID',
  `_tenant_id` bigint(20) NOT NULL DEFAULT '1' COMMENT '商户ID',
  `user_id` bigint(20) NOT NULL DEFAULT '-1' COMMENT '用户ID',
  `uri` varchar(5000) DEFAULT NULL COMMENT '请求地址',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `modified` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='请求记录表';

-- --------------------------------------------------------

--
-- 表的结构 `user_share`
--

CREATE TABLE `user_share` (
  `id` bigint(20) NOT NULL,
  `share_key` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '唯一key',
  `_tenant_id` bigint(20) NOT NULL COMMENT '租户id',
  `user_id` bigint(20) NOT NULL COMMENT '用户id',
  `type` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '分享类型',
  `target_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '类型可能存在的id',
  `content` json DEFAULT NULL COMMENT '分享内容',
  `expire` datetime DEFAULT NULL COMMENT '过期时间',
  `modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- --------------------------------------------------------

--
-- 表的结构 `user_target_relation`
--

CREATE TABLE `user_target_relation` (
  `id` bigint(20) NOT NULL,
  `_tenant_id` bigint(20) NOT NULL COMMENT '商户ID',
  `user_id` bigint(20) NOT NULL COMMENT '用户ID',
  `target_type` varchar(32) NOT NULL COMMENT '目标对象类型',
  `target_id` bigint(20) NOT NULL COMMENT '目标对象ID',
  `type` varchar(32) NOT NULL DEFAULT 'Add' COMMENT '关系类型',
  `extra` json DEFAULT NULL,
  `modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户与目标对象关系表';

-- --------------------------------------------------------

--
-- 表的结构 `workflow_config`
--

CREATE TABLE `workflow_config` (
  `id` bigint(20) NOT NULL,
  `_tenant_id` bigint(20) NOT NULL DEFAULT '1' COMMENT '租户ID',
  `space_id` bigint(20) NOT NULL COMMENT '空间ID',
  `creator_id` bigint(20) NOT NULL COMMENT '创建用户ID',
  `name` varchar(100) NOT NULL COMMENT '工作流名称',
  `description` text COMMENT '工作流描述信息',
  `icon` varchar(255) DEFAULT NULL COMMENT 'icon图片地址',
  `start_node_id` bigint(20) DEFAULT NULL COMMENT '起始节点ID',
  `end_node_id` bigint(20) DEFAULT NULL COMMENT '结束节点ID',
  `publish_status` enum('Developing','Applying','Published') NOT NULL DEFAULT 'Developing' COMMENT '发布状态',
  `ext` json DEFAULT NULL,
  `modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `workflow_node_config`
--

CREATE TABLE `workflow_node_config` (
  `id` bigint(20) NOT NULL,
  `_tenant_id` bigint(20) NOT NULL DEFAULT '1' COMMENT '商户ID',
  `name` varchar(100) DEFAULT NULL COMMENT '节点名称',
  `icon` varchar(255) DEFAULT NULL COMMENT '图标',
  `description` text COMMENT '描述',
  `workflow_id` bigint(20) DEFAULT NULL COMMENT '工作流ID',
  `type` varchar(32) NOT NULL COMMENT '节点类型',
  `config` json DEFAULT NULL COMMENT '详细配置',
  `loop_node_id` bigint(20) DEFAULT NULL COMMENT '循环体中各节点记录循环节点ID',
  `next_node_ids` json DEFAULT NULL COMMENT '下级节点ID列表',
  `inner_node_Ids` json DEFAULT NULL COMMENT '循环节点的内部节点',
  `inner_start_node_id` bigint(20) DEFAULT NULL COMMENT '循环节点内部开始节点',
  `inner_end_node_id` bigint(20) DEFAULT NULL COMMENT '循环节点内部结束节点',
  `modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体组件配置';

--
-- 转储表的索引
--

--
-- 表的索引 `agent_component_config`
--
ALTER TABLE `agent_component_config`
  ADD PRIMARY KEY (`id`);

--
-- 表的索引 `agent_config`
--
ALTER TABLE `agent_config`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_space_id` (`space_id`) USING BTREE;

--
-- 表的索引 `agent_temp_chat`
--
ALTER TABLE `agent_temp_chat`
  ADD PRIMARY KEY (`id`);

--
-- 表的索引 `card`
--
ALTER TABLE `card`
  ADD PRIMARY KEY (`id`);

--
-- 表的索引 `category`
--
ALTER TABLE `category`
  ADD PRIMARY KEY (`id`),
  ADD UNIQUE KEY `uk_tenant_code` (`_tenant_id`,`type`,`code`) USING BTREE;

--
-- 表的索引 `config_history`
--
ALTER TABLE `config_history`
  ADD PRIMARY KEY (`id`),
  ADD KEY `target_id` (`target_id`);

--
-- 表的索引 `content_i18n`
--
ALTER TABLE `content_i18n`
  ADD PRIMARY KEY (`id`),
  ADD UNIQUE KEY `uk_lang_content` (`model`,`mid`,`lang`,`field_key`) USING BTREE;

--
-- 表的索引 `conversation`
--
ALTER TABLE `conversation`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_user_id` (`user_id`) USING BTREE,
  ADD KEY `idx_uid` (`uid`) USING BTREE,
  ADD KEY `idx_type_status` (`type`,`task_status`) USING BTREE,
  ADD KEY `sandbox_server_id` (`sandbox_server_id`),
  ADD KEY `modified` (`modified`);

--
-- 表的索引 `conversation_message`
--
ALTER TABLE `conversation_message`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_user_id` (`user_id`),
  ADD KEY `idx_conversation_id` (`conversation_id`),
  ADD KEY `idx_message_id` (`message_id`);

--
-- 表的索引 `custom_field_definition`
--
ALTER TABLE `custom_field_definition`
  ADD PRIMARY KEY (`id`),
  ADD UNIQUE KEY `uk_table_field` (`table_id`,`field_name`),
  ADD KEY `idx_table_id` (`table_id`);

--
-- 表的索引 `custom_page_build`
--
ALTER TABLE `custom_page_build`
  ADD PRIMARY KEY (`id`);

--
-- 表的索引 `custom_page_config`
--
ALTER TABLE `custom_page_config`
  ADD PRIMARY KEY (`id`),
  ADD UNIQUE KEY `uk_base_path` (`base_path`);

--
-- 表的索引 `custom_page_conversation`
--
ALTER TABLE `custom_page_conversation`
  ADD PRIMARY KEY (`id`);

--
-- 表的索引 `custom_page_domain`
--
ALTER TABLE `custom_page_domain`
  ADD PRIMARY KEY (`id`),
  ADD UNIQUE KEY `uk_domain` (`domain`),
  ADD KEY `idx_tenant_project` (`_tenant_id`,`project_id`),
  ADD KEY `idx_domain` (`domain`);

--
-- 表的索引 `custom_table_definition`
--
ALTER TABLE `custom_table_definition`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_table_name` (`table_name`);

--
-- 表的索引 `eco_market_client_config`
--
ALTER TABLE `eco_market_client_config`
  ADD PRIMARY KEY (`id`),
  ADD UNIQUE KEY `uk_uid` (`uid`,`_tenant_id`);

--
-- 表的索引 `eco_market_client_publish_config`
--
ALTER TABLE `eco_market_client_publish_config`
  ADD PRIMARY KEY (`id`),
  ADD UNIQUE KEY `uk_uid` (`uid`,`_tenant_id`);

--
-- 表的索引 `eco_market_client_secret`
--
ALTER TABLE `eco_market_client_secret`
  ADD PRIMARY KEY (`id`),
  ADD UNIQUE KEY `uk_client_id` (`client_id`) COMMENT '客户端ID唯一索引';

--
-- 表的索引 `knowledge_config`
--
ALTER TABLE `knowledge_config`
  ADD PRIMARY KEY (`id`);

--
-- 表的索引 `knowledge_document`
--
ALTER TABLE `knowledge_document`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_kb_id` (`kb_id`),
  ADD KEY `idx_id_kb_id_index` (`space_id`,`kb_id`);

--
-- 表的索引 `knowledge_qa_segment`
--
ALTER TABLE `knowledge_qa_segment`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_doc_id` (`doc_id`),
  ADD KEY `idx_kb_id_space_id_doc_id_index` (`kb_id`,`space_id`,`doc_id`);

--
-- 表的索引 `knowledge_raw_segment`
--
ALTER TABLE `knowledge_raw_segment`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_doc_id` (`doc_id`),
  ADD KEY `idx_kb_id` (`kb_id`),
  ADD KEY `idx_space_id_kb_id_doc_id_index` (`space_id`,`kb_id`,`doc_id`),
  ADD KEY `idx_kb_fulltext_sync` (`kb_id`,`fulltext_sync_status`);

--
-- 表的索引 `knowledge_task`
--
ALTER TABLE `knowledge_task`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_status_kb_id_doc_id` (`status`,`kb_id`,`doc_id`),
  ADD KEY `idx_doc_id` (`doc_id`);

--
-- 表的索引 `knowledge_task_history`
--
ALTER TABLE `knowledge_task_history`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_status_kb_id_doc_id` (`status`,`kb_id`,`doc_id`);

--
-- 表的索引 `mcp_config`
--
ALTER TABLE `mcp_config`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_uid` (`uid`);

--
-- 表的索引 `memory_unit`
--
ALTER TABLE `memory_unit`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_tenant_user` (`_tenant_id`,`user_id`),
  ADD KEY `idx_agent` (`agent_id`),
  ADD KEY `idx_category` (`category`,`sub_category`),
  ADD KEY `idx_status` (`status`),
  ADD KEY `idx_created` (`created`);

--
-- 表的索引 `memory_unit_tag`
--
ALTER TABLE `memory_unit_tag`
  ADD PRIMARY KEY (`id`),
  ADD UNIQUE KEY `uk_tag_memory` (`tag_name`,`memory_id`) USING BTREE,
  ADD KEY `idx_tenant_user` (`_tenant_id`,`user_id`),
  ADD KEY `idx_memory` (`memory_id`);

--
-- 表的索引 `model_config`
--
ALTER TABLE `model_config`
  ADD PRIMARY KEY (`id`);

--
-- 表的索引 `notify_message`
--
ALTER TABLE `notify_message`
  ADD PRIMARY KEY (`id`);

--
-- 表的索引 `notify_message_user`
--
ALTER TABLE `notify_message_user`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_user_notify` (`user_id`,`notify_id`) USING BTREE;

--
-- 表的索引 `pf_retry_data`
--
ALTER TABLE `pf_retry_data`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_project_app_bean_method` (`project_code`,`app_code`,`bean_name`,`method_name`,`tid`) USING BTREE;

--
-- 表的索引 `plugin_config`
--
ALTER TABLE `plugin_config`
  ADD PRIMARY KEY (`id`);

--
-- 表的索引 `published`
--
ALTER TABLE `published`
  ADD PRIMARY KEY (`id`),
  ADD KEY `target_id` (`target_id`);

--
-- 表的索引 `published_statistics`
--
ALTER TABLE `published_statistics`
  ADD PRIMARY KEY (`id`),
  ADD UNIQUE KEY `uk_target_id` (`target_id`,`target_type`,`name`) USING BTREE;

--
-- 表的索引 `publish_apply`
--
ALTER TABLE `publish_apply`
  ADD PRIMARY KEY (`id`),
  ADD KEY `_tenant_id` (`_tenant_id`,`target_type`);

--
-- 表的索引 `sandbox_config`
--
ALTER TABLE `sandbox_config`
  ADD PRIMARY KEY (`id`),
  ADD UNIQUE KEY `uk_scope_user_key` (`scope`,`user_id`,`config_key`),
  ADD KEY `idx_scope` (`scope`),
  ADD KEY `idx_user_id` (`user_id`),
  ADD KEY `idx_config_key` (`config_key`),
  ADD KEY `idx_is_active` (`is_active`);

--
-- 表的索引 `sandbox_proxy`
--
ALTER TABLE `sandbox_proxy`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_proxy_key` (`proxy_key`),
  ADD KEY `idx_sandbox_id` (`sandbox_id`),
  ADD KEY `idx_user_id` (`user_id`);

--
-- 表的索引 `schedule_task`
--
ALTER TABLE `schedule_task`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_user_status` (`status`) USING BTREE,
  ADD KEY `status` (`status`),
  ADD KEY `task_id` (`task_id`);

--
-- 表的索引 `skill_config`
--
ALTER TABLE `skill_config`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_space_id` (`space_id`);

--
-- 表的索引 `space`
--
ALTER TABLE `space`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_tenant_id` (`_tenant_id`);

--
-- 表的索引 `space_user`
--
ALTER TABLE `space_user`
  ADD PRIMARY KEY (`id`),
  ADD UNIQUE KEY `uk_space_user` (`space_id`,`user_id`) USING BTREE;

--
-- 表的索引 `system_config`
--
ALTER TABLE `system_config`
  ADD PRIMARY KEY (`config_id`),
  ADD UNIQUE KEY `name` (`name`);

--
-- 表的索引 `sys_data_permission`
--
ALTER TABLE `sys_data_permission`
  ADD PRIMARY KEY (`id`),
  ADD UNIQUE KEY `idx_tenant_target_type_id` (`_tenant_id`,`target_type`,`target_id`);

--
-- 表的索引 `sys_group`
--
ALTER TABLE `sys_group`
  ADD PRIMARY KEY (`id`),
  ADD UNIQUE KEY `uk_tenant_code` (`_tenant_id`,`code`);

--
-- 表的索引 `sys_group_menu`
--
ALTER TABLE `sys_group_menu`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_group_id` (`group_id`),
  ADD KEY `idx_menu_id` (`menu_id`);

--
-- 表的索引 `sys_menu`
--
ALTER TABLE `sys_menu`
  ADD PRIMARY KEY (`id`),
  ADD UNIQUE KEY `uk_tenant_code` (`_tenant_id`,`code`),
  ADD KEY `idx_parent_id` (`parent_id`);

--
-- 表的索引 `sys_menu_resource`
--
ALTER TABLE `sys_menu_resource`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_menu_id` (`menu_id`),
  ADD KEY `idx_resource_id` (`resource_id`);

--
-- 表的索引 `sys_operator_log`
--
ALTER TABLE `sys_operator_log`
  ADD PRIMARY KEY (`id`);

--
-- 表的索引 `sys_resource`
--
ALTER TABLE `sys_resource`
  ADD PRIMARY KEY (`id`),
  ADD UNIQUE KEY `uk_tenant_code` (`_tenant_id`,`code`),
  ADD KEY `idx_parent_id` (`parent_id`);

--
-- 表的索引 `sys_role`
--
ALTER TABLE `sys_role`
  ADD PRIMARY KEY (`id`),
  ADD UNIQUE KEY `uk_tenant_code` (`_tenant_id`,`code`);

--
-- 表的索引 `sys_role_menu`
--
ALTER TABLE `sys_role_menu`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_menu_id` (`menu_id`),
  ADD KEY `idx_role_id` (`role_id`);

--
-- 表的索引 `sys_subject_permission`
--
ALTER TABLE `sys_subject_permission`
  ADD PRIMARY KEY (`id`),
  ADD UNIQUE KEY `uk_subject_target` (`_tenant_id`,`subject_type`,`subject_id`,`target_type`,`target_id`),
  ADD KEY `idx_subject_type_id` (`subject_type`,`subject_id`),
  ADD KEY `idx_target_type_id` (`target_type`,`target_id`);

--
-- 表的索引 `sys_user_group`
--
ALTER TABLE `sys_user_group`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_group_id` (`group_id`),
  ADD KEY `idx_user_id` (`user_id`);

--
-- 表的索引 `sys_user_role`
--
ALTER TABLE `sys_user_role`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_role_id` (`role_id`),
  ADD KEY `idx_user_id` (`user_id`);

--
-- 表的索引 `tenant`
--
ALTER TABLE `tenant`
  ADD PRIMARY KEY (`id`);

--
-- 表的索引 `tenant_config`
--
ALTER TABLE `tenant_config`
  ADD PRIMARY KEY (`id`),
  ADD UNIQUE KEY `name` (`name`,`_tenant_id`) USING BTREE;

--
-- 表的索引 `tool`
--
ALTER TABLE `tool`
  ADD PRIMARY KEY (`tool_id`);

--
-- 表的索引 `user`
--
ALTER TABLE `user`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_phone` (`phone`,`_tenant_id`) USING BTREE,
  ADD KEY `idx_uid` (`uid`,`_tenant_id`) USING BTREE;

--
-- 表的索引 `user_access_key`
--
ALTER TABLE `user_access_key`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_user_id` (`user_id`) USING BTREE,
  ADD KEY `idx_access_key` (`access_key`) USING BTREE;

--
-- 表的索引 `user_agent_sort`
--
ALTER TABLE `user_agent_sort`
  ADD PRIMARY KEY (`id`),
  ADD UNIQUE KEY `uk_user_id_category` (`user_id`,`category`),
  ADD KEY `idx_user_id` (`user_id`) USING BTREE;

--
-- 表的索引 `user_metric`
--
ALTER TABLE `user_metric`
  ADD PRIMARY KEY (`id`),
  ADD UNIQUE KEY `uk_metric` (`user_id`,`biz_type`,`period_type`,`period`),
  ADD KEY `idx_user_id` (`user_id`),
  ADD KEY `idx_biz_type` (`biz_type`);

--
-- 表的索引 `user_req`
--
ALTER TABLE `user_req`
  ADD PRIMARY KEY (`id`),
  ADD UNIQUE KEY `idx_unique_user_date` (`_tenant_id`,`user_id`,`dt`),
  ADD KEY `idx_dt` (`dt`);

--
-- 表的索引 `user_request`
--
ALTER TABLE `user_request`
  ADD PRIMARY KEY (`id`),
  ADD KEY `modified` (`modified`),
  ADD KEY `idx_user_request_tenant_created` (`_tenant_id`,`created`);

--
-- 表的索引 `user_share`
--
ALTER TABLE `user_share`
  ADD PRIMARY KEY (`id`),
  ADD KEY `share_key` (`share_key`),
  ADD KEY `user_id` (`user_id`),
  ADD KEY `idx_type` (`type`,`target_id`) USING BTREE;

--
-- 表的索引 `user_target_relation`
--
ALTER TABLE `user_target_relation`
  ADD PRIMARY KEY (`id`),
  ADD UNIQUE KEY `uk_user_agent_type` (`user_id`,`target_type`,`target_id`,`type`) USING BTREE,
  ADD KEY `idx_user_id` (`user_id`) USING BTREE;

--
-- 表的索引 `workflow_config`
--
ALTER TABLE `workflow_config`
  ADD PRIMARY KEY (`id`);

--
-- 表的索引 `workflow_node_config`
--
ALTER TABLE `workflow_node_config`
  ADD PRIMARY KEY (`id`);

--
-- 在导出的表使用AUTO_INCREMENT
--

--
-- 使用表AUTO_INCREMENT `agent_component_config`
--
ALTER TABLE `agent_component_config`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT;

--
-- 使用表AUTO_INCREMENT `agent_config`
--
ALTER TABLE `agent_config`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '智能体ID';

--
-- 使用表AUTO_INCREMENT `agent_temp_chat`
--
ALTER TABLE `agent_temp_chat`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT;

--
-- 使用表AUTO_INCREMENT `card`
--
ALTER TABLE `card`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT;

--
-- 使用表AUTO_INCREMENT `category`
--
ALTER TABLE `category`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID';

--
-- 使用表AUTO_INCREMENT `config_history`
--
ALTER TABLE `config_history`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT;

--
-- 使用表AUTO_INCREMENT `content_i18n`
--
ALTER TABLE `content_i18n`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID';

--
-- 使用表AUTO_INCREMENT `conversation`
--
ALTER TABLE `conversation`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT;

--
-- 使用表AUTO_INCREMENT `conversation_message`
--
ALTER TABLE `conversation_message`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID';

--
-- 使用表AUTO_INCREMENT `custom_field_definition`
--
ALTER TABLE `custom_field_definition`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID';

--
-- 使用表AUTO_INCREMENT `custom_page_build`
--
ALTER TABLE `custom_page_build`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID';

--
-- 使用表AUTO_INCREMENT `custom_page_config`
--
ALTER TABLE `custom_page_config`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID';

--
-- 使用表AUTO_INCREMENT `custom_page_conversation`
--
ALTER TABLE `custom_page_conversation`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID';

--
-- 使用表AUTO_INCREMENT `custom_page_domain`
--
ALTER TABLE `custom_page_domain`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键';

--
-- 使用表AUTO_INCREMENT `custom_table_definition`
--
ALTER TABLE `custom_table_definition`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID';

--
-- 使用表AUTO_INCREMENT `eco_market_client_config`
--
ALTER TABLE `eco_market_client_config`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键id';

--
-- 使用表AUTO_INCREMENT `eco_market_client_publish_config`
--
ALTER TABLE `eco_market_client_publish_config`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键id';

--
-- 使用表AUTO_INCREMENT `eco_market_client_secret`
--
ALTER TABLE `eco_market_client_secret`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键id';

--
-- 使用表AUTO_INCREMENT `knowledge_config`
--
ALTER TABLE `knowledge_config`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键id';

--
-- 使用表AUTO_INCREMENT `knowledge_document`
--
ALTER TABLE `knowledge_document`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键id';

--
-- 使用表AUTO_INCREMENT `knowledge_qa_segment`
--
ALTER TABLE `knowledge_qa_segment`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键id';

--
-- 使用表AUTO_INCREMENT `knowledge_raw_segment`
--
ALTER TABLE `knowledge_raw_segment`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键id';

--
-- 使用表AUTO_INCREMENT `knowledge_task`
--
ALTER TABLE `knowledge_task`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键id';

--
-- 使用表AUTO_INCREMENT `knowledge_task_history`
--
ALTER TABLE `knowledge_task_history`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键id';

--
-- 使用表AUTO_INCREMENT `mcp_config`
--
ALTER TABLE `mcp_config`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT;

--
-- 使用表AUTO_INCREMENT `memory_unit`
--
ALTER TABLE `memory_unit`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID';

--
-- 使用表AUTO_INCREMENT `memory_unit_tag`
--
ALTER TABLE `memory_unit_tag`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID';

--
-- 使用表AUTO_INCREMENT `model_config`
--
ALTER TABLE `model_config`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT;

--
-- 使用表AUTO_INCREMENT `notify_message`
--
ALTER TABLE `notify_message`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT;

--
-- 使用表AUTO_INCREMENT `notify_message_user`
--
ALTER TABLE `notify_message_user`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT;

--
-- 使用表AUTO_INCREMENT `pf_retry_data`
--
ALTER TABLE `pf_retry_data`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT;

--
-- 使用表AUTO_INCREMENT `plugin_config`
--
ALTER TABLE `plugin_config`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT;

--
-- 使用表AUTO_INCREMENT `published`
--
ALTER TABLE `published`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT;

--
-- 使用表AUTO_INCREMENT `published_statistics`
--
ALTER TABLE `published_statistics`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT;

--
-- 使用表AUTO_INCREMENT `publish_apply`
--
ALTER TABLE `publish_apply`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT;

--
-- 使用表AUTO_INCREMENT `sandbox_config`
--
ALTER TABLE `sandbox_config`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID';

--
-- 使用表AUTO_INCREMENT `sandbox_proxy`
--
ALTER TABLE `sandbox_proxy`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT;

--
-- 使用表AUTO_INCREMENT `schedule_task`
--
ALTER TABLE `schedule_task`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT;

--
-- 使用表AUTO_INCREMENT `skill_config`
--
ALTER TABLE `skill_config`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID';

--
-- 使用表AUTO_INCREMENT `space`
--
ALTER TABLE `space`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT;

--
-- 使用表AUTO_INCREMENT `space_user`
--
ALTER TABLE `space_user`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT;

--
-- 使用表AUTO_INCREMENT `system_config`
--
ALTER TABLE `system_config`
  MODIFY `config_id` int(11) NOT NULL AUTO_INCREMENT;

--
-- 使用表AUTO_INCREMENT `sys_data_permission`
--
ALTER TABLE `sys_data_permission`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID';

--
-- 使用表AUTO_INCREMENT `sys_group`
--
ALTER TABLE `sys_group`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID';

--
-- 使用表AUTO_INCREMENT `sys_group_menu`
--
ALTER TABLE `sys_group_menu`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID';

--
-- 使用表AUTO_INCREMENT `sys_menu`
--
ALTER TABLE `sys_menu`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '菜单ID';

--
-- 使用表AUTO_INCREMENT `sys_menu_resource`
--
ALTER TABLE `sys_menu_resource`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID';

--
-- 使用表AUTO_INCREMENT `sys_operator_log`
--
ALTER TABLE `sys_operator_log`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '自增主键';

--
-- 使用表AUTO_INCREMENT `sys_resource`
--
ALTER TABLE `sys_resource`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '资源ID';

--
-- 使用表AUTO_INCREMENT `sys_role`
--
ALTER TABLE `sys_role`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '角色ID';

--
-- 使用表AUTO_INCREMENT `sys_role_menu`
--
ALTER TABLE `sys_role_menu`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID';

--
-- 使用表AUTO_INCREMENT `sys_subject_permission`
--
ALTER TABLE `sys_subject_permission`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID';

--
-- 使用表AUTO_INCREMENT `sys_user_group`
--
ALTER TABLE `sys_user_group`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID';

--
-- 使用表AUTO_INCREMENT `sys_user_role`
--
ALTER TABLE `sys_user_role`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID';

--
-- 使用表AUTO_INCREMENT `tenant`
--
ALTER TABLE `tenant`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT;

--
-- 使用表AUTO_INCREMENT `tenant_config`
--
ALTER TABLE `tenant_config`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT;

--
-- 使用表AUTO_INCREMENT `tool`
--
ALTER TABLE `tool`
  MODIFY `tool_id` bigint(20) NOT NULL AUTO_INCREMENT;

--
-- 使用表AUTO_INCREMENT `user`
--
ALTER TABLE `user`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '平台用户ID';

--
-- 使用表AUTO_INCREMENT `user_access_key`
--
ALTER TABLE `user_access_key`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT;

--
-- 使用表AUTO_INCREMENT `user_agent_sort`
--
ALTER TABLE `user_agent_sort`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT;

--
-- 使用表AUTO_INCREMENT `user_metric`
--
ALTER TABLE `user_metric`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT;

--
-- 使用表AUTO_INCREMENT `user_req`
--
ALTER TABLE `user_req`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID';

--
-- 使用表AUTO_INCREMENT `user_request`
--
ALTER TABLE `user_request`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID';

--
-- 使用表AUTO_INCREMENT `user_share`
--
ALTER TABLE `user_share`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT;

--
-- 使用表AUTO_INCREMENT `user_target_relation`
--
ALTER TABLE `user_target_relation`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT;

--
-- 使用表AUTO_INCREMENT `workflow_config`
--
ALTER TABLE `workflow_config`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT;

--
-- 使用表AUTO_INCREMENT `workflow_node_config`
--
ALTER TABLE `workflow_node_config`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT;
COMMIT;


INSERT INTO `tenant` (`id`, `name`, `description`, `status`, `domain`, `version`, `modified`, `created`) VALUES
(1, '女娲智能体OS', '女娲智能体OS', 'Enabled', 'localhost', '1.0.7.1', '2026-03-14 16:43:22', '2024-12-31 15:50:49');
INSERT INTO `user` (`id`, `_tenant_id`, `uid`, `user_name`, `nick_name`, `avatar`, `status`, `role`, `password`, `reset_pass`, `email`, `phone`, `last_login_time`, `created`, `modified`) VALUES
(1, 1, '1', 'admin', '管理员', '', 'Enabled', 'Admin', 'c8dc90833d72c907436763077ce76c67', 1, 'admin@nuwax.com', '18888888888', '2026-03-12 14:22:19', '2025-01-02 18:22:46', '2026-03-12 14:22:19');
INSERT INTO `space` (`id`, `_tenant_id`, `name`, `description`, `icon`, `creator_id`, `type`, `receive_publish`, `allow_develop`, `yn`, `modified`, `created`) VALUES
(1, 1, '个人空间', '个人空间', NULL, 1, 'Personal', 1, 1, 0, '2025-01-03 19:18:11', '2025-01-03 19:18:11');
INSERT INTO `space_user` (`id`, `_tenant_id`, `space_id`, `user_id`, `role`, `modified`, `created`) VALUES
(1, 1, 1, 1, 'Owner', '2025-01-03 19:18:11', '2025-01-03 19:18:11');


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


ALTER TABLE `user_access_key` ADD `name` VARCHAR(255) NULL COMMENT '密钥备注名称' AFTER `user_id`;
ALTER TABLE `user_access_key` ADD `expire` DATETIME NULL COMMENT '过期时间，留空为不过期' AFTER `config`, ADD `status` TINYINT(1) NOT NULL DEFAULT '1' COMMENT '状态，启用 1; 停用 0' AFTER `expire`;
ALTER TABLE `schedule_task` ADD `server_info` VARCHAR(32) NULL COMMENT '执行任务的服务器信息' AFTER `error`;
ALTER TABLE `model_config` ADD `usage_scenario` VARCHAR(255) NULL COMMENT '可用的场景范围' AFTER `access_control`;
ALTER TABLE `agent_config` ADD `allow_other_model` TINYINT(1) NOT NULL DEFAULT '0' COMMENT '是否允许在对话框中选择其他模型' AFTER `hide_desktop`, ADD `allow_at_skill` TINYINT(1) NOT NULL DEFAULT '1' COMMENT '是否允许@技能' AFTER `allow_other_model`, ADD `allow_private_sandbox` TINYINT(1) NOT NULL DEFAULT '1' COMMENT '是否允许使用自己的电脑' AFTER `allow_at_skill`;
ALTER TABLE `user` ADD `lang` VARCHAR(32) NULL COMMENT '用户当前语言环境' AFTER `last_login_time`;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
