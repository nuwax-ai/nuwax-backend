package com.xspaceagi.system.infra.dao.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.xspaceagi.system.infra.dao.entity.UserConfig;

public interface UserConfigService extends IService<UserConfig> {

    /**
     * 按 key 取单行；不存在返回 null
     */
    UserConfig getByKey(Long userId, String configKey);

    /**
     * 按 key 原子写入（存在则覆盖，不存在则插入）
     *
     * @return true 表示本次为新增，false 表示覆盖更新
     */
    boolean upsert(Long tenantId, Long userId, String configKey, Object configValue, String description);

    /**
     * 按 key 幂等删除
     *
     * @return true 表示确实删掉了一行
     */
    boolean deleteByKey(Long userId, String configKey);

    /**
     * 某用户当前配置项数量
     */
    long countByUser(Long userId);
}
