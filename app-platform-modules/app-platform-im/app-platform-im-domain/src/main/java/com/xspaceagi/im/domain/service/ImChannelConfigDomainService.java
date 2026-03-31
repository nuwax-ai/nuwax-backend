package com.xspaceagi.im.domain.service;

import com.xspaceagi.im.infra.dao.enitity.ImChannelConfig;

import java.util.List;

public interface ImChannelConfigDomainService {

    ImChannelConfig findOne(String channel, String targetType, String imTargetId);

    /**
     * 按渠道+目标类型+targetId 查找（不限制 enabled），用于同一 iLink 账号重复扫码刷新 token
     */
    ImChannelConfig findOneIgnoreEnabled(String channel, String targetType, String imTargetId);

    /**
     * 新增配置
     */
    ImChannelConfig add(ImChannelConfig config);

    /**
     * 查询配置列表
     */
    List<ImChannelConfig> list(ImChannelConfig query);

    /**
     * 分页查询配置列表
     *
     * @param query 查询条件
     * @param offset 偏移量
     * @param limit 限制数量
     * @return 配置列表
     */
    List<ImChannelConfig> listByPage(ImChannelConfig query, int offset, int limit);

    /**
     * 根据 ID 查询配置
     */
    ImChannelConfig getById(Long id);

    /**
     * 根据 ID 更新配置
     */
    ImChannelConfig updateById(ImChannelConfig config);

    /**
     * 启用/禁用配置
     */
    boolean updateEnabled(ImChannelConfig config);

    /**
     * 删除配置（逻辑删除）
     *
     * @param id 配置ID
     * @return 是否删除成功
     */
    boolean delete(Long id);
}
