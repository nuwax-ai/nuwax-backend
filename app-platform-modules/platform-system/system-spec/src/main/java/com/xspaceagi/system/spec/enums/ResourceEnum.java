package com.xspaceagi.system.spec.enums;

import lombok.Getter;

/**
 * 系统内置资源
 */
@Getter
public enum ResourceEnum {

    ROOT(ResourceTypeEnum.MODULE, "root", "根节点", "根节点"),

    // ================== 主页模块 ==================
//    HOMEPAGE(ResourceTypeEnum.MODULE, "homepage", "主页模块", "root"),

    // ================== 生态市场模块 ==================
//    ECO_MARKET(ResourceTypeEnum.MODULE, "eco_market", "生态市场模块", "root"),

    // ================== 空间模块 ==================
    SPACE(ResourceTypeEnum.MODULE, "space", "空间模块", "root"),
//    SPACE_QUERY_LIST(ResourceTypeEnum.OPERATION, "space_query_list", "查询空间列表", "space"),
//    SPACE_QUERY_DETAIL(ResourceTypeEnum.OPERATION, "space_query_detail", "查询空间详情", "space"),
    SPACE_CREATE(ResourceTypeEnum.OPERATION, "space_create", "创建团队空间", "space"),
    SPACE_MODIFY(ResourceTypeEnum.OPERATION, "space_modify", "编辑", "space"),
    SPACE_DELETE(ResourceTypeEnum.OPERATION, "space_delete", "删除空间", "space"),
    SPACE_TRANSFER(ResourceTypeEnum.OPERATION, "space_transfer", "转让空间", "space"),
    SPACE_QUERY_USER_LIST(ResourceTypeEnum.OPERATION, "space_query_user_list", "查询成员列表", "space"),
    SPACE_ADD_USER(ResourceTypeEnum.OPERATION, "space_add_user", "添加成员", "space"),
    SPACE_DELETE_USER(ResourceTypeEnum.OPERATION, "space_delete_user", "删除成员", "space"),


    // ================== 智能体开发模块 ==================
    AGENT_DEV(ResourceTypeEnum.MODULE, "agent_dev", "智能体开发模块", "root"),

    AGENT_CREATE_CHAT_BOT(ResourceTypeEnum.OPERATION, "agent_create_chat_bot", "创建智能体-问答型", "agent_dev"),
    AGENT_CREATE_TASK_AGENT(ResourceTypeEnum.OPERATION, "agent_create_task_agent", "创建智能体-通用型", "agent_dev"),
    AGENT_PUBLISH(ResourceTypeEnum.OPERATION, "agent_publish", "发布", "agent_dev"),
    AGENT_IMPORT(ResourceTypeEnum.OPERATION, "agent_import", "导入配置", "agent_dev"),
    AGENT_QUERY_LIST(ResourceTypeEnum.OPERATION, "agent_query_list", "查询列表", "agent_dev"),
    AGENT_QUERY_DETAIL(ResourceTypeEnum.OPERATION, "agent_query_detail", "查询详情", "agent_dev"),
    AGENT_COLLECT(ResourceTypeEnum.OPERATION, "agent_collect", "收藏", "agent_dev"),
    AGENT_MODIFY(ResourceTypeEnum.OPERATION, "agent_modify", "编辑", "agent_dev"),
    AGENT_COPY_TO_SPACE(ResourceTypeEnum.OPERATION, "agent_copy_to_space", "复制到空间", "agent_dev"),
    AGENT_MIGRATE(ResourceTypeEnum.OPERATION, "agent_migrate", "迁移", "agent_dev"),
    AGENT_API_KEY(ResourceTypeEnum.OPERATION, "agent_api_key", "API Key", "agent_dev"),
    AGENT_EXPORT(ResourceTypeEnum.OPERATION, "agent_export", "导出配置", "agent_dev"),
//    AGENT_LOG(ResourceTypeEnum.OPERATION, "agent_log", "日志", "agent_dev"),
    AGENT_DELETE(ResourceTypeEnum.OPERATION, "agent_delete", "删除", "agent_dev"),
    //AGENT_ANALYSE(ResourceTypeEnum.OPERATION, "agent_analyse", "分析", "agent_dev"),
    AGENT_TEMP_CONVERSATION(ResourceTypeEnum.OPERATION, "agent_temp_conversation", "创建临时会话", "agent_dev"),

    // ================== 网页应用开发模块 ==================
    PAGE_APP_DEV(ResourceTypeEnum.MODULE, "page_app_dev", "网页应用开发模块", "root"),

    PAGE_APP_CREATE(ResourceTypeEnum.OPERATION, "page_app_create", "创建", "page_app_dev"),
    PAGE_APP_QUERY_LIST(ResourceTypeEnum.OPERATION, "page_app_query_list", "查询列表", "page_app_dev"),
    PAGE_APP_QUERY_DETAIL(ResourceTypeEnum.OPERATION, "page_app_query_detail", "查询详情", "page_app_dev"),
    PAGE_APP_MODIFY(ResourceTypeEnum.OPERATION, "page_app_modify", "编辑", "page_app_dev"),
    PAGE_APP_CONFIG_PROXY(ResourceTypeEnum.OPERATION, "page_app_config_proxy", "反向代理配置", "page_app_dev"),
    PAGE_APP_CONFIG_PATH(ResourceTypeEnum.OPERATION, "page_app_config_path", "路径参数配置", "page_app_dev"),
    //PAGE_APP_CONFIG_AUTH(ResourceTypeEnum.OPERATION, "page_app_config_auth", "认证配置", "page_app_dev"),
    PAGE_APP_COPY_TO_SPACE(ResourceTypeEnum.OPERATION, "page_app_copy_to_space", "复制到空间", "page_app_dev"),
    PAGE_APP_BIND_DOMAIN(ResourceTypeEnum.OPERATION, "page_app_bind_domain", "绑定域名", "page_app_dev"),
    PAGE_APP_IMPORT(ResourceTypeEnum.OPERATION, "page_app_import", "导入项目", "page_app_dev"),
    PAGE_APP_EXPORT(ResourceTypeEnum.OPERATION, "page_app_export", "导出项目", "page_app_dev"),
    PAGE_APP_RESTART_SERVER(ResourceTypeEnum.OPERATION, "page_app_restart_server", "重启服务器", "page_app_dev"),
    PAGE_APP_PUBLISH(ResourceTypeEnum.OPERATION, "page_app_publish", "发布", "page_app_dev"),
    PAGE_APP_DELETE(ResourceTypeEnum.OPERATION, "page_app_delete", "删除", "page_app_dev"),
    PAGE_APP_AI_CHAT(ResourceTypeEnum.OPERATION, "page_app_ai_chat", "AI对话", "page_app_dev"),
    PAGE_APP_MODIFY_FILE(ResourceTypeEnum.OPERATION, "page_app_modify_file", "修改文件", "page_app_dev"),
    PAGE_APP_UPLOAD_FILE(ResourceTypeEnum.OPERATION, "page_app_upload_file", "上传文件", "page_app_dev"),
    PAGE_APP_ROLLBACK_VERSION(ResourceTypeEnum.OPERATION, "page_app_rollback_version", "回滚版本", "page_app_dev"),

    // ================== 组件库开发模块 ==================
    COMPONENT_LIB_DEV(ResourceTypeEnum.MODULE, "component_lib_dev", "组件库开发模块", "root"),

    COMPONENT_LIB_CREATE(ResourceTypeEnum.OPERATION, "component_lib_create", "创建", "component_lib_dev"),
    COMPONENT_LIB_PUBLISH(ResourceTypeEnum.OPERATION, "component_lib_publish", "发布", "component_lib_dev"),
    COMPONENT_LIB_QUERY_LIST(ResourceTypeEnum.OPERATION, "component_lib_query_list", "查询列表", "component_lib_dev"),
    COMPONENT_LIB_QUERY_DETAIL(ResourceTypeEnum.OPERATION, "component_lib_query_detail", "查询详情", "component_lib_dev"),
    COMPONENT_LIB_MODIFY(ResourceTypeEnum.OPERATION, "component_lib_modify", "编辑", "component_lib_dev"),
    COMPONENT_LIB_COPY_TO_SPACE(ResourceTypeEnum.OPERATION, "component_lib_copy_to_space", "复制到空间", "component_lib_dev"),
    COMPONENT_LIB_COPY(ResourceTypeEnum.OPERATION, "component_lib_copy", "复制", "component_lib_dev"),
    COMPONENT_LIB_IMPORT(ResourceTypeEnum.OPERATION, "component_lib_import", "导入配置", "component_lib_dev"),
    COMPONENT_LIB_EXPORT(ResourceTypeEnum.OPERATION, "component_lib_export", "导出配置", "component_lib_dev"),
//    COMPONENT_LIB_LOG(ResourceTypeEnum.OPERATION, "component_lib_log", "日志", "component_lib_dev"),
    COMPONENT_LIB_DELETE(ResourceTypeEnum.OPERATION, "component_lib_delete", "删除", "component_lib_dev"),

    // ================== 技能开发模块 ==================
    SKILL_DEV(ResourceTypeEnum.MODULE, "skill_dev", "技能开发模块", "root"),

    SKILL_CREATE(ResourceTypeEnum.OPERATION, "skill_create", "创建技能", "skill_dev"),
    SKILL_QUERY_LIST(ResourceTypeEnum.OPERATION, "skill_query_list", "查询列表", "skill_dev"),
    SKILL_QUERY_DETAIL(ResourceTypeEnum.OPERATION, "skill_query_detail", "查询详情", "skill_dev"),
    SKILL_MODIFY(ResourceTypeEnum.OPERATION, "skill_modify", "编辑", "skill_dev"),
    SKILL_IMPORT(ResourceTypeEnum.OPERATION, "skill_import", "导入技能", "skill_dev"),
    SKILL_EXPORT(ResourceTypeEnum.OPERATION, "skill_export", "导出技能", "skill_dev"),
    SKILL_COPY_TO_SPACE(ResourceTypeEnum.OPERATION, "skill_copy_to_space", "复制到空间", "skill_dev"),
    SKILL_PUBLISH(ResourceTypeEnum.OPERATION, "skill_publish", "发布", "skill_dev"),
    SKILL_DELETE(ResourceTypeEnum.OPERATION, "skill_delete", "删除", "skill_dev"),

    // ================== MCP管理模块 ==================
    MCP_DEV(ResourceTypeEnum.MODULE, "mcp_dev", "MCP管理模块", "root"),

    MCP_CREATE(ResourceTypeEnum.OPERATION, "mcp_create", "创建MCP服务", "mcp_dev"),
    MCP_QUERY_LIST(ResourceTypeEnum.OPERATION, "mcp_query_list", "查询列表", "mcp_dev"),
    MCP_QUERY_DETAIL(ResourceTypeEnum.OPERATION, "mcp_query_detail", "查询详情", "mcp_dev"),
    MCP_SAVE(ResourceTypeEnum.OPERATION, "mcp_save", "保存", "mcp_dev"),
    MCP_EXPORT(ResourceTypeEnum.OPERATION, "mcp_export", "导出", "mcp_dev"),
//    MCP_LOG(ResourceTypeEnum.OPERATION, "mcp_log", "日志", "mcp_dev"),
    MCP_DELETE(ResourceTypeEnum.OPERATION, "mcp_delete", "删除", "mcp_dev"),
    MCP_STOP(ResourceTypeEnum.OPERATION, "mcp_stop", "停止服务", "mcp_dev"),

    // ================== IM渠道 ==================
    IM_CONFIG(ResourceTypeEnum.MODULE, "im_config", "IM渠道配置模块", "root"),
    IM_CONFIG_QUERY_LIST(ResourceTypeEnum.MODULE, "im_config_query_list", "查询配置列表", "im_config"),
    IM_CONFIG_QUERY_DETAIL(ResourceTypeEnum.MODULE, "im_config_query_detail", "查询配置详情", "im_config"),
    IM_CONFIG_ADD(ResourceTypeEnum.MODULE, "im_config_add", "添加配置", "im_config"),
    IM_CONFIG_MODIFY(ResourceTypeEnum.MODULE, "im_config_modify", "编辑配置", "im_config"),
    IM_CONFIG_DELETE(ResourceTypeEnum.MODULE, "im_config_delete", "删除配置", "im_config"),
//    IM_CONFIG_ENABLE(ResourceTypeEnum.MODULE, "im_config_enable", "启用/禁用配置", "im_config"),

    // ================== 任务中心模块 ==================
    SPACE_TASK_DEV(ResourceTypeEnum.MODULE, "space_task_dev", "任务中心模块", "root"),

    SPACE_TASK_CREATE(ResourceTypeEnum.OPERATION, "space_task_create", "创建任务", "space_task_dev"),
    SPACE_TASK_QUERY_LIST(ResourceTypeEnum.OPERATION, "space_task_query_list", "查询列表", "space_task_dev"),
    SPACE_TASK_EXECUTE_MANUAL(ResourceTypeEnum.OPERATION, "space_task_execute_manual", "手动执行", "space_task_dev"),
    SPACE_TASK_CANCEL(ResourceTypeEnum.OPERATION, "space_task_cancel", "取消任务", "space_task_dev"),
    SPACE_TASK_ENABLE(ResourceTypeEnum.OPERATION, "space_task_enable", "启用", "space_task_dev"),
//    SPACE_TASK_EXECUTE_RECORD(ResourceTypeEnum.OPERATION, "space_task_execute_record", "执行记录", "space_task_dev"),
    SPACE_TASK_MODIFY(ResourceTypeEnum.OPERATION, "space_task_modify", "编辑", "space_task_dev"),
    SPACE_TASK_DELETE(ResourceTypeEnum.OPERATION, "space_task_delete", "删除", "space_task_dev"),

    // ================== 空间日志查询模块 ==================
    SPACE_LOG_QUERY(ResourceTypeEnum.MODULE, "space_log_query", "空间日志查询模块", "root"),

    SPACE_LOG_QUERY_LIST(ResourceTypeEnum.OPERATION, "space_log_query_list", "查询列表", "space_log_query"),
    SPACE_LOG_QUERY_DETAIL(ResourceTypeEnum.OPERATION, "space_log_query_detail", "详情", "space_log_query"),

    // ================== 广场模块 ==================
//    SQUARE(ResourceTypeEnum.MODULE, "space_square", "空间广场模块", "root"),
//
//    SQUARE_QUERY_LIST(ResourceTypeEnum.OPERATION, "square_query_list", "查询列表", "space_square"),
//    SQUARE_QUERY_DETAIL(ResourceTypeEnum.OPERATION, "square_query_detail", "查询详情", "space_square"),
//    SQUARE_OFFLINE(ResourceTypeEnum.OPERATION, "square_offline", "下架", "space_square"),
//    SQUARE_COLLECT(ResourceTypeEnum.OPERATION, "square_collect", "收藏", "space_square"),
//    SQUARE_COPY_TEMPLATE(ResourceTypeEnum.OPERATION, "square_copy_template", "复制模板(智能体、插件、工作流)", "space_square"),
//    SQUARE_EXPORT(ResourceTypeEnum.OPERATION, "square_export", "导出", "space_square"),

    // ================== 用户管理模块 ==================
    USER_MANAGE(ResourceTypeEnum.MODULE, "user_manage", "用户管理模块", "root"),

    USER_MANAGE_QUERY(ResourceTypeEnum.OPERATION, "user_manage_query", "查询用户", "user_manage"),
    USER_MANAGE_ADD(ResourceTypeEnum.OPERATION, "user_manage_add", "添加用户", "user_manage"),
    USER_MANAGE_SEND_MESSAGE(ResourceTypeEnum.OPERATION, "user_manage_send_message", "消息发送", "user_manage"),
    USER_MANAGE_ENABLE(ResourceTypeEnum.OPERATION, "user_manage_enable", "启用", "user_manage"),
    USER_MANAGE_DISABLE(ResourceTypeEnum.OPERATION, "user_manage_disable", "禁用", "user_manage"),
    USER_MANAGE_MODIFY(ResourceTypeEnum.OPERATION, "user_manage_modify", "修改", "user_manage"),
    USER_MANAGE_BIND_ROLE(ResourceTypeEnum.OPERATION, "user_manage_bind_role", "绑定角色", "user_manage"),
    USER_MANAGE_BIND_GROUP(ResourceTypeEnum.OPERATION, "user_manage_bind_group", "绑定用户组", "user_manage"),
    USER_MANAGE_QUERY_MENU_PERMISSION(ResourceTypeEnum.OPERATION, "user_manage_query_menu_permission", "查看权限", "user_manage"),
    USER_MANAGE_QUERY_DATA_PERMISSION(ResourceTypeEnum.OPERATION, "user_manage_query_data_permission", "数据权限", "user_manage"),


    // ================== 发布审核模块 ==================
    PUBLISH_AUDIT(ResourceTypeEnum.MODULE, "publish_audit", "发布审核模块", "root"),

    PUBLISH_AUDIT_QUERY_LIST(ResourceTypeEnum.OPERATION, "publish_audit_query_list", "查询列表", "publish_audit"),
    PUBLISH_AUDIT_PASS(ResourceTypeEnum.OPERATION, "publish_audit_pass", "通过", "publish_audit"),
    PUBLISH_AUDIT_REJECT(ResourceTypeEnum.OPERATION, "publish_audit_reject", "拒绝", "publish_audit"),
    PUBLISH_AUDIT_QUERY_DETAIL(ResourceTypeEnum.OPERATION, "publish_audit_query_detail", "查看", "publish_audit"),

    // ================== 已发布管理模块 ==================
    PUBLISHED_MANAGE(ResourceTypeEnum.MODULE, "published_manage", "已发布管理模块", "root"),

    PUBLISHED_MANAGE_QUERY_LIST(ResourceTypeEnum.OPERATION, "published_manage_query_list", "查询列表", "published_manage"),
    PUBLISHED_MANAGE_OFFLINE(ResourceTypeEnum.OPERATION, "published_manage_offline", "下架", "published_manage"),
    PUBLISHED_MANAGE_QUERY_DETAIL(ResourceTypeEnum.OPERATION, "published_manage_query_detail", "查看", "published_manage"),

    // ================== 模型管理模块 ==================
    MODEL_MANAGE(ResourceTypeEnum.MODULE, "model_manage", "模型管理模块", "root"),

    MODEL_MANAGE_QUERY_LIST(ResourceTypeEnum.OPERATION, "model_manage_query_list", "查询列表", "model_manage"),
    MODEL_MANAGE_ADD(ResourceTypeEnum.OPERATION, "model_manage_add", "添加模型", "model_manage"),
    MODEL_MANAGE_MODIFY(ResourceTypeEnum.OPERATION, "model_manage_modify", "编辑", "model_manage"),
    MODEL_MANAGE_DELETE(ResourceTypeEnum.OPERATION, "model_manage_delete", "删除", "model_manage"),
    MODEL_MANAGE_ACCESS_CONTROL(ResourceTypeEnum.OPERATION, "model_manage_access_control", "访问授权", "model_manage"),

    // ================== 系统设置模块 ==================
    SYSTEM_SETTING(ResourceTypeEnum.MODULE, "system_setting", "系统设置模块", "root"),

    SYSTEM_SETTING_BASIC(ResourceTypeEnum.MODULE, "system_setting_basic", "基础配置", "system_setting"),
    SYSTEM_SETTING_MODEL_DEFAULT(ResourceTypeEnum.MODULE, "system_setting_model_default", "默认模型配置", "system_setting"),
    SYSTEM_SETTING_SITE_AGENT(ResourceTypeEnum.MODULE, "system_setting_site_agent", "站点智能体设置", "system_setting"),
    SYSTEM_SETTING_SAVE(ResourceTypeEnum.OPERATION, "system_setting_save", "保存", "system_setting"),

    // ================== 主题配置模块 ==================
    SYSTEM_THEME_CONFIG(ResourceTypeEnum.MODULE, "system_theme_config", "主题配置模块", "root"),

    SYSTEM_THEME_CONFIG_SAVE(ResourceTypeEnum.OPERATION, "system_theme_config_save", "保存配置", "system_theme_config"),

    // ================== 沙盒配置模块 ==================
    SANDBOX_CONFIG(ResourceTypeEnum.MODULE, "sandbox_config", "沙盒配置模块", "root"),

    SANDBOX_CONFIG_QUERY(ResourceTypeEnum.OPERATION, "sandbox_config_query", "查询", "sandbox_config"),
    SANDBOX_CONFIG_ADD(ResourceTypeEnum.OPERATION, "sandbox_config_add", "添加", "sandbox_config"),
    SANDBOX_CONFIG_MODIFY(ResourceTypeEnum.OPERATION, "sandbox_config_modify", "编辑", "sandbox_config"),
    SANDBOX_CONFIG_SAVE(ResourceTypeEnum.OPERATION, "sandbox_config_save", "保存", "sandbox_config"),
    SANDBOX_CONFIG_DELETE(ResourceTypeEnum.OPERATION, "sandbox_config_delete", "删除", "sandbox_config"),
//    SANDBOX_CONFIG_ENABLE(ResourceTypeEnum.OPERATION, "sandbox_config_enable", "启用/禁用", "sandbox_config"),

    // ================== 分类管理模块 ==================
    CATEGORY_CONFIG(ResourceTypeEnum.MODULE, "category_config", "分类管理模块", "root"),

    CATEGORY_CONFIG_QUERY(ResourceTypeEnum.OPERATION, "category_config_query", "查询", "category_config"),
    CATEGORY_CONFIG_ADD(ResourceTypeEnum.OPERATION, "category_config_add", "添加", "category_config"),
    CATEGORY_CONFIG_MODIFY(ResourceTypeEnum.OPERATION, "category_config_modify", "编辑", "category_config"),
    CATEGORY_CONFIG_DELETE(ResourceTypeEnum.OPERATION, "category_config_delete", "删除", "category_config"),

    // ================== 系统概览模块 ==================
    SYSTEM_DASHBOARD(ResourceTypeEnum.MODULE, "system_dashboard", "系统概览查询", "root"),

    // ================== 系统任务管理模块 ==================
    SYSTEM_TASK_MANAGE(ResourceTypeEnum.MODULE, "system_task_manage", "系统任务管理模块", "root"),

    TASK_MANAGE_QUERY_LIST(ResourceTypeEnum.OPERATION, "task_manage_query_list", "查询列表", "system_task_manage"),
    TASK_MANAGE_EXECUTE_MANUAL(ResourceTypeEnum.OPERATION, "task_manage_execute_manual", "手动执行", "system_task_manage"),
    TASK_MANAGE_ENABLE(ResourceTypeEnum.OPERATION, "task_manage_enable", "启用", "system_task_manage"),
    TASK_MANAGE_CANCEL(ResourceTypeEnum.OPERATION, "task_manage_cancel", "停用", "system_task_manage"),
    TASK_MANAGE_EXECUTE_RECORD(ResourceTypeEnum.OPERATION, "task_manage_execute_record", "执行记录", "system_task_manage"),
    TASK_MANAGE_MODIFY(ResourceTypeEnum.OPERATION, "task_manage_modify", "编辑", "system_task_manage"),
    TASK_MANAGE_DELETE(ResourceTypeEnum.OPERATION, "task_manage_delete", "删除", "system_task_manage"),

    // ================== 权限管理模块 ==================
    // 1级模块
    PERMISSION_MANAGE(ResourceTypeEnum.MODULE, "permission_manage", "权限管理模块", "root"),

    // 2级模块 - 资源管理模块
    RESOURCE_MANAGE(ResourceTypeEnum.MODULE, "resource_manage", "资源管理模块", "permission_manage"),

    RESOURCE_MANAGE_QUERY(ResourceTypeEnum.OPERATION, "resource_manage_query", "查询", "resource_manage"),
    RESOURCE_MANAGE_ADD(ResourceTypeEnum.OPERATION, "resource_manage_add", "新增", "resource_manage"),
    RESOURCE_MANAGE_MODIFY(ResourceTypeEnum.OPERATION, "resource_manage_modify", "编辑", "resource_manage"),
    RESOURCE_MANAGE_DELETE(ResourceTypeEnum.OPERATION, "resource_manage_delete", "删除", "resource_manage"),

    // 2级模块 - 菜单管理模块
    MENU_MANAGE(ResourceTypeEnum.MODULE, "menu_manage", "菜单管理模块", "permission_manage"),

    MENU_MANAGE_QUERY(ResourceTypeEnum.OPERATION, "menu_manage_query", "查询", "menu_manage"),
    MENU_MANAGE_ADD(ResourceTypeEnum.OPERATION, "menu_manage_add", "新增", "menu_manage"),
    MENU_MANAGE_MODIFY(ResourceTypeEnum.OPERATION, "menu_manage_modify", "编辑", "menu_manage"),
    MENU_MANAGE_DELETE(ResourceTypeEnum.OPERATION, "menu_manage_delete", "删除", "menu_manage"),

    // 2级模块 - 角色管理模块
    ROLE_MANAGE(ResourceTypeEnum.MODULE, "role_manage", "角色管理模块", "permission_manage"),

    ROLE_MANAGE_QUERY(ResourceTypeEnum.OPERATION, "role_manage_query", "查询", "role_manage"),
    ROLE_MANAGE_ADD(ResourceTypeEnum.OPERATION, "role_manage_add", "新增", "role_manage"),
    ROLE_MANAGE_MODIFY(ResourceTypeEnum.OPERATION, "role_manage_modify", "编辑", "role_manage"),
    ROLE_MANAGE_DELETE(ResourceTypeEnum.OPERATION, "role_manage_delete", "删除", "role_manage"),
    ROLE_MANAGE_BIND_MENU(ResourceTypeEnum.OPERATION, "role_manage_bind_menu", "菜单权限", "role_manage"),
    ROLE_MANAGE_BIND_USER(ResourceTypeEnum.OPERATION, "role_manage_bind_user", "绑定用户", "role_manage"),
    ROLE_MANAGE_BIND_DATA(ResourceTypeEnum.OPERATION, "role_manage_bind_data", "数据权限", "role_manage"),

    // 2级模块 - 用户组管理模块
    USER_GROUP_MANAGE(ResourceTypeEnum.MODULE, "user_group_manage", "用户组管理模块", "permission_manage"),

    USER_GROUP_MANAGE_QUERY(ResourceTypeEnum.OPERATION, "user_group_manage_query", "查询", "user_group_manage"),
    USER_GROUP_MANAGE_ADD(ResourceTypeEnum.OPERATION, "user_group_manage_add", "新增", "user_group_manage"),
    USER_GROUP_MANAGE_MODIFY(ResourceTypeEnum.OPERATION, "user_group_manage_modify", "编辑", "user_group_manage"),
    USER_GROUP_MANAGE_DELETE(ResourceTypeEnum.OPERATION, "user_group_manage_delete", "删除", "user_group_manage"),
    USER_GROUP_MANAGE_BIND_MENU(ResourceTypeEnum.OPERATION, "user_group_manage_bind_menu", "菜单权限", "user_group_manage"),
    USER_GROUP_MANAGE_BIND_USER(ResourceTypeEnum.OPERATION, "user_group_manage_bind_user", "绑定用户", "user_group_manage"),
    USER_GROUP_MANAGE_BIND_DATA(ResourceTypeEnum.OPERATION, "user_group_manage_bind_data", "数据权限", "user_group_manage"),


    // ================== 系统运行日志模块 ==================
    // 1级模块
    SYSTEM_LOG(ResourceTypeEnum.MODULE, "system_log", "系统日志模块", "root"),
    // 2级模块 - 系统运行日志
    SYSTEM_RUNNING_LOG(ResourceTypeEnum.MODULE, "system_running_log", "系统运行日志模块", "system_log"),

    SYSTEM_RUNNING_LOG_QUERY_LIST(ResourceTypeEnum.OPERATION, "system_running_log_query_list", "查询列表", "system_running_log"),
    SYSTEM_RUNNING_LOG_QUERY_DETAIL(ResourceTypeEnum.OPERATION, "system_running_log_query_detail", "查询详情", "system_running_log"),


    // ================== 内容管理模块 ==================
    // 1级模块
    CONTENT_MANAGE(ResourceTypeEnum.MODULE, "content_manage", "内容管理模块", "root"),

    // 2级模块 - 空间管理
    CONTENT_SPACE(ResourceTypeEnum.MODULE, "content_space", "空间管理", "content_manage"),

    CONTENT_SPACE_QUERY_LIST(ResourceTypeEnum.OPERATION, "content_space_query_list", "查询列表", "content_space"),
    CONTENT_SPACE_QUERY_DETAIL(ResourceTypeEnum.OPERATION, "content_space_query_detail", "查询详情", "content_space"),
    CONTENT_SPACE_DELETE(ResourceTypeEnum.OPERATION, "content_space_delete", "删除", "content_space"),

    // 2级模块 - 智能体管理
    CONTENT_AGENT(ResourceTypeEnum.MODULE, "content_agent", "智能体管理", "content_manage"),

    CONTENT_AGENT_QUERY_LIST(ResourceTypeEnum.OPERATION, "content_agent_query_list", "查询列表", "content_agent"),
    CONTENT_AGENT_QUERY_DETAIL(ResourceTypeEnum.OPERATION, "content_agent_query_detail", "查询详情", "content_agent"),
    CONTENT_AGENT_DELETE(ResourceTypeEnum.OPERATION, "content_agent_delete", "删除", "content_agent"),
    CONTENT_AGENT_ACCESS_CONTROL(ResourceTypeEnum.OPERATION, "content_agent_access_control", "访问授权", "content_agent"),

    // 2级模块 - 网页应用管理
    CONTENT_PAGE_APP(ResourceTypeEnum.MODULE, "content_page_app", "网页应用管理", "content_manage"),

    CONTENT_PAGE_APP_QUERY_LIST(ResourceTypeEnum.OPERATION, "content_page_app_query_list", "查询列表", "content_page_app"),
    CONTENT_PAGE_APP_QUERY_DETAIL(ResourceTypeEnum.OPERATION, "content_page_app_query_detail", "查询详情", "content_page_app"),
    CONTENT_PAGE_APP_DELETE(ResourceTypeEnum.OPERATION, "content_page_app_delete", "删除", "content_page_app"),
    CONTENT_PAGE_APP_ACCESS_CONTROL(ResourceTypeEnum.OPERATION, "content_page_app_access_control", "访问授权", "content_page_app"),

    // 2级模块 - 知识库管理
    CONTENT_KNOWLEDGE(ResourceTypeEnum.MODULE, "content_knowledge", "知识库管理", "content_manage"),

    CONTENT_KNOWLEDGE_QUERY_LIST(ResourceTypeEnum.OPERATION, "content_knowledge_query_list", "查询列表", "content_knowledge"),
    CONTENT_KNOWLEDGE_QUERY_DETAIL(ResourceTypeEnum.OPERATION, "content_knowledge_query_detail", "查询详情", "content_knowledge"),
    CONTENT_KNOWLEDGE_DELETE(ResourceTypeEnum.OPERATION, "content_knowledge_delete", "删除", "content_knowledge"),

    // 2级模块 - 数据表管理
    CONTENT_DATATABLE(ResourceTypeEnum.MODULE, "content_datatable", "数据表管理", "content_manage"),

    CONTENT_DATATABLE_QUERY_LIST(ResourceTypeEnum.OPERATION, "content_datatable_query_list", "查询列表", "content_datatable"),
    CONTENT_DATATABLE_QUERY_DETAIL(ResourceTypeEnum.OPERATION, "content_datatable_query_detail", "查询详情", "content_datatable"),
    CONTENT_DATATABLE_DELETE(ResourceTypeEnum.OPERATION, "content_datatable_delete", "删除", "content_datatable"),

    // 2级模块 - 工作流管理
    CONTENT_WORKFLOW(ResourceTypeEnum.MODULE, "content_workflow", "工作流管理", "content_manage"),

    CONTENT_WORKFLOW_QUERY_LIST(ResourceTypeEnum.OPERATION, "content_workflow_query_list", "查询列表", "content_workflow"),
    CONTENT_WORKFLOW_QUERY_DETAIL(ResourceTypeEnum.OPERATION, "content_workflow_query_detail", "查询详情", "content_workflow"),
    CONTENT_WORKFLOW_DELETE(ResourceTypeEnum.OPERATION, "content_workflow_delete", "删除", "content_workflow"),

    // 2级模块 - 插件管理
    CONTENT_PLUGIN(ResourceTypeEnum.MODULE, "content_plugin", "插件管理", "content_manage"),

    CONTENT_PLUGIN_QUERY_LIST(ResourceTypeEnum.OPERATION, "content_plugin_query_list", "查询列表", "content_plugin"),
    CONTENT_PLUGIN_QUERY_DETAIL(ResourceTypeEnum.OPERATION, "content_plugin_query_detail", "查询详情", "content_plugin"),
    CONTENT_PLUGIN_DELETE(ResourceTypeEnum.OPERATION, "content_plugin_delete", "删除", "content_plugin"),

    // 2级模块 - MCP管理
    CONTENT_MCP(ResourceTypeEnum.MODULE, "content_mcp", "MCP管理", "content_manage"),

    CONTENT_MCP_QUERY_LIST(ResourceTypeEnum.OPERATION, "content_mcp_query_list", "查询列表", "content_mcp"),
    CONTENT_MCP_QUERY_DETAIL(ResourceTypeEnum.OPERATION, "content_mcp_query_detail", "查询详情", "content_mcp"),
    CONTENT_MCP_DELETE(ResourceTypeEnum.OPERATION, "content_mcp_delete", "删除", "content_mcp"),

    // 2级模块 - 技能管理
    CONTENT_SKILL(ResourceTypeEnum.MODULE, "content_skill", "技能管理", "content_manage"),

    CONTENT_SKILL_QUERY_LIST(ResourceTypeEnum.OPERATION, "content_skill_query_list", "查询列表", "content_skill"),
    CONTENT_SKILL_QUERY_DETAIL(ResourceTypeEnum.OPERATION, "content_skill_query_detail", "查询详情", "content_skill"),
    CONTENT_SKILL_DELETE(ResourceTypeEnum.OPERATION, "content_skill_delete", "删除", "content_skill");


    private final ResourceTypeEnum type;
    private final String code;
    private final String name;
    private final String parentCode;

    ResourceEnum(ResourceTypeEnum type, String code, String name, String parentCode) {
        this.type = type;
        this.code = code;
        this.name = name;
        this.parentCode = parentCode;
    }

    /**
     * 根据code获取枚举
     */
    public static ResourceEnum getByCode(String code) {
        if (code == null) {
            return null;
        }
        for (ResourceEnum resourceEnum : values()) {
            if (resourceEnum.getCode().equals(code)) {
                return resourceEnum;
            }
        }
        return null;
    }
    }
