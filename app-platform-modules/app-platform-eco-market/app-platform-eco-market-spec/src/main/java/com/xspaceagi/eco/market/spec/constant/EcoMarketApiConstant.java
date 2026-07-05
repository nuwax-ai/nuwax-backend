package com.xspaceagi.eco.market.spec.constant;

/**
 * 生态市场API常量
 * 用于统一维护生态市场模块的API路径
 */
public class EcoMarketApiConstant {

    /**
     * 服务器端API根路径
     */
    public static final String SERVER_API_BASE = "/api/system/eco/market/server";

    /**
     * 客户端API根路径
     */
    public static final String CLIENT_API_BASE = "/api/system/eco/market/client";

    /**
     * 服务器密钥相关API路径
     */
    public static class ServerSecret {
        /**
         * 服务器密钥API根路径
         */
        public static final String BASE = SERVER_API_BASE + "/secret";

        /**
         * 客户端注册API路径
         */
        public static final String REGISTER = BASE + "/register";

        /**
         * 验证客户端密钥API路径
         */
        public static final String VALIDATE = BASE + "/validate";
    }

    /**
     * 服务器配置相关API路径
     */
    public static class ServerConfig {
        /**
         * 服务器配置API根路径
         */
        public static final String BASE = SERVER_API_BASE + "/config";

        /**
         * 保存配置API路径
         */
        public static final String SAVE = BASE + "/save";

        /**
         * 查询详情API路径
         */
        public static final String DETAIL = BASE + "/detail";

        /**
         * 批量查询详情API路径
         */
        public static final String BATCH_DETAIL = BASE + "/batchDetail";

        /**
         * 发布配置API路径
         */
        public static final String PUBLISH = BASE + "/publish";

        /**
         * 下线配置API路径
         */
        public static final String OFFLINE = BASE + "/offline";

        /**
         * 审批配置API路径
         */
        public static final String APPROVE = BASE + "/approve";

        /**
         * 页面zip包上传API路径
         */
        public static final String UPLOAD_PAGE_ZIP = BASE + "/uploadPageZip";
    }

    /**
     * 服务器已发布配置相关API路径
     */
    public static class ServerPublishConfig {
        /**
         * 服务器已发布配置API根路径
         */
        public static final String BASE = SERVER_API_BASE + "/publishConfig";

        /**
         * 已发布配置列表查询API路径
         */
        public static final String LIST = BASE + "/list";
    }

    /**
     * 客户端密钥相关API路径
     */
    public static class ClientSecret {
        /**
         * 客户端密钥API根路径
         */
        public static final String BASE = CLIENT_API_BASE + "/secret";
    }

    /**
     * 客户端配置相关API路径
     */
    public static class ClientConfig {
        /**
         * 客户端配置API根路径
         */
        public static final String BASE = CLIENT_API_BASE + "/config";
    }

    /**
     * 客户端已发布配置相关API路径
     */
    public static class ClientPublishConfig {
        /**
         * 客户端已发布配置API根路径
         */
        public static final String BASE = CLIENT_API_BASE + "/publish/config";
    }

    /**
     * 导入数据相关API路径
     */
    public static class ImportData {
        /**
         * 获取导入数据API路径
         */
        public static final String GET_IMPORT_DATA = SERVER_API_BASE + "/import/data";
    }
} 