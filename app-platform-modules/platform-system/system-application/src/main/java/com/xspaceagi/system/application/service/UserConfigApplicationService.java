package com.xspaceagi.system.application.service;

import com.xspaceagi.system.application.dto.UserConfigItemDto;

import java.util.List;
import java.util.Map;

/**
 * 用户个人 key-value 配置：全部方法只作用于传入的 userId（由调用方从登录态取出），不开放跨用户读写
 */
public interface UserConfigApplicationService {

    /**
     * 该用户全部配置的 key→value
     */
    Map<String, Object> queryConfigMap(Long userId);

    /**
     * 该用户全部配置项（含备注与时间戳），按 key 升序
     */
    List<UserConfigItemDto> queryConfigList(Long userId);

    /**
     * 取单个 key 的值；不存在返回 null
     */
    Object queryConfig(Long userId, String key);

    /**
     * 按 key 原子写入（存在则覆盖，不存在则新增）
     */
    void setConfig(Long userId, String key, Object value, String description);

    /**
     * 按 key 删除，幂等
     */
    void deleteConfig(Long userId, String key);
}
