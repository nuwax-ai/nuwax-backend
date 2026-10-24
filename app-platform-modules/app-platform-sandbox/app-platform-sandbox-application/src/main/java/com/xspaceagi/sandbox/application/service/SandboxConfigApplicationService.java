package com.xspaceagi.sandbox.application.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xspaceagi.sandbox.application.dto.SandboxConfigDto;
import com.xspaceagi.sandbox.application.dto.SandboxConfigQueryDto;
import com.xspaceagi.sandbox.application.dto.SandboxGlobalConfigDto;

import java.util.List;

/**
 * 沙盒配置应用服务接口
 */
public interface SandboxConfigApplicationService {

    /**
     * 分页查询配置列表
     *
     * @param queryDto 查询条件
     * @return 分页结果
     */
    Page<SandboxConfigDto> pageQuery(SandboxConfigQueryDto queryDto);

    /**
     * 查询配置详情
     *
     * @param id 配置ID
     * @return 配置详情
     */
    SandboxConfigDto getById(Long id);

    /**
     * 根据配置键查询配置详情
     *
     * @param key 配置键
     * @return 配置详情
     */
    SandboxConfigDto getByKey(String key);

    /**
     * 根据用户ID + 设备ID查询配置详情（device_id 维度）
     *
     * @param userId   用户ID
     * @param deviceId 客户端设备ID
     * @return 配置详情；不存在时返回 null
     */
    SandboxConfigDto getByDeviceId(Long userId, String deviceId);

    /**
     * 查询用户配置列表
     *
     * @param userId 用户ID（为空时查询当前用户）
     * @return 配置列表
     */
    List<SandboxConfigDto> listUserConfigsByType(Long userId);

    /**
     * 查询全局配置列表
     *
     * @return 配置列表
     */
    List<SandboxConfigDto> listGlobalConfigsByType();

    List<SandboxConfigDto> listPageDevelopmentSandboxes();

    boolean hasGlobalConfigsForSelect();

    /**
     * 创建配置
     *
     * @param dto 配置信息
     */
    void create(SandboxConfigDto dto, boolean userAdd);

    /**
     * 更新配置
     *
     * @param dto 配置信息
     */
    void update(SandboxConfigDto dto);

    /**
     * 删除配置
     *
     * @param id 配置ID
     */
    void delete(Long id);

    /**
     * 启用/禁用配置
     *
     * @param id 配置ID
     */
    void toggle(Long id);

    /**
     * 查询全局配置
     *
     * @return 全局配置
     */
    SandboxGlobalConfigDto getGlobalConfig(Long tenantId);

    void updateGlobalConfig(Long tenantId, SandboxGlobalConfigDto dto);

    void testConnection(Long sandboxId);
}
