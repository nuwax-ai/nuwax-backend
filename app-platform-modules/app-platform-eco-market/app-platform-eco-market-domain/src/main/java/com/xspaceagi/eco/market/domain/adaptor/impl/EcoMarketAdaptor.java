package com.xspaceagi.eco.market.domain.adaptor.impl;

import java.util.Collections;
import java.util.List;

import com.xspaceagi.mcp.sdk.IMcpApiService;
import com.xspaceagi.mcp.sdk.dto.McpDto;

import org.springframework.stereotype.Service;

import com.xspaceagi.agent.core.sdk.IAgentRpcService;
import com.xspaceagi.agent.core.sdk.dto.AgentInfoDto;
import com.xspaceagi.agent.core.sdk.dto.PluginEnableOrUpdateDto;
import com.xspaceagi.agent.core.sdk.dto.TemplateEnableOrUpdateDto;
import com.xspaceagi.agent.core.sdk.enums.TargetTypeEnum;
import com.xspaceagi.eco.market.domain.adaptor.IEcoMarketAdaptor;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.EcoMarketException;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class EcoMarketAdaptor implements IEcoMarketAdaptor {

    @Resource
    private IAgentRpcService agentRpcService;

    /**
     * MCP API服务
     */
    @Resource
    private IMcpApiService mcpApiService;

    @Override
    public List<AgentInfoDto> queryAgentInfoList(List<Long> agentIds) {
        try {
            log.info("Agent RPC list agents: agentIds={}", agentIds);
            if (agentIds == null || agentIds.isEmpty()) {
                return Collections.emptyList();
            }

            var result = agentRpcService.queryAgentInfoList(agentIds);
            if (result == null || !result.isSuccess()) {
                log.error("List agents failed: agentIds={}, errorCode={}, errorMsg={}",
                        agentIds, result != null ? result.getCode() : "NULL",
                        result != null ? result.getMessage() : "NULL");
                throw EcoMarketException.build(BizExceptionCodeEnum.configNotFound, "查询Agent信息列表失败");
            }

            return result.getData() != null ? result.getData() : Collections.emptyList();
        } catch (EcoMarketException e) {
            // 已经是我们自定义的异常,直接抛出
            throw e;
        } catch (Exception e) {
            log.error("List agents error: agentIds={}", agentIds, e);
            throw EcoMarketException.build(BizExceptionCodeEnum.configNotFound,
                    "查询Agent信息列表异常: " + e.getMessage());
        }
    }

    @Override
    public String queryPluginConfig(Long pluginId, String paramJson) {
        try {
            log.info("Agent RPC get plugin config: pluginId={}, paramJson={}", pluginId, paramJson);
            if (pluginId == null) {
                throw new IllegalArgumentException("Plugin ID cannot be empty");
            }

            var result = agentRpcService.queryPluginConfig(pluginId, paramJson);
            if (result == null || !result.isSuccess()) {
                log.error("Get plugin config failed: pluginId={}, errorCode={}, errorMsg={}",
                        pluginId, result != null ? result.getCode() : "NULL",
                        result != null ? result.getMessage() : "NULL");
                throw EcoMarketException.build(BizExceptionCodeEnum.configNotFound, "查询插件配置失败");
            }

            return result.getData();
        } catch (EcoMarketException e) {
            throw e;
        } catch (Exception e) {
            log.error("Get plugin config error: pluginId={}", pluginId, e);
            throw EcoMarketException.build(BizExceptionCodeEnum.configNotFound, "查询插件配置异常: " + e.getMessage());
        }
    }

    @Override
    public Long pluginEnableOrUpdate(PluginEnableOrUpdateDto pluginEnableOrUpdateDto) {
        try {
            log.info("Agent RPC enable/update plugin: pluginEnableOrUpdateDto={}", pluginEnableOrUpdateDto);
            if (pluginEnableOrUpdateDto == null) {
                throw new IllegalArgumentException("Plugin enable or update DTO cannot be empty");
            }

            var result = agentRpcService.pluginEnableOrUpdate(pluginEnableOrUpdateDto);
            if (result == null || !result.isSuccess()) {
                log.error("Enable/update plugin failed: errorCode={}, errorMsg={}",
                        result != null ? result.getCode() : "NULL", result != null ? result.getMessage() : "NULL");
                throw EcoMarketException.build(BizExceptionCodeEnum.configNotFound, "启用或更新插件失败");
            }

            return result.getData();
        } catch (EcoMarketException e) {
            throw e;
        } catch (Exception e) {
            log.error("Enable/update plugin error", e);
            throw EcoMarketException.build(BizExceptionCodeEnum.configNotFound, "启用或更新插件异常: " + e.getMessage());
        }
    }

    @Override
    public Void disablePlugin(Long pluginId) {
        try {
            log.info("Agent RPC disable plugin: pluginId={}", pluginId);
            if (pluginId == null) {
                throw new IllegalArgumentException("Plugin ID cannot be empty");
            }

            var result = agentRpcService.disablePlugin(pluginId);
            if (result == null || !result.isSuccess()) {
                log.error("Disable plugin failed: pluginId={}, errorCode={}, errorMsg={}",
                        pluginId, result != null ? result.getCode() : "NULL",
                        result != null ? result.getMessage() : "NULL");
                throw EcoMarketException.build(BizExceptionCodeEnum.configNotFound, "禁用插件失败");
            }

            return null;
        } catch (EcoMarketException e) {
            throw e;
        } catch (Exception e) {
            log.error("Disable plugin error: pluginId={}", pluginId, e);
            throw EcoMarketException.build(BizExceptionCodeEnum.configNotFound, "禁用插件异常: " + e.getMessage());
        }
    }

    @Override
    public String queryTemplateConfig(TargetTypeEnum targetType, Long targetId) {
        try {
            log.info("Agent RPC get template config: targetType={}, targetId={}", targetType, targetId);
            if (targetType == null || targetId == null) {
                throw new IllegalArgumentException("Target type or target ID cannot be empty");
            }

            var result = agentRpcService.queryTemplateConfig(targetType, targetId);
            if (result == null || !result.isSuccess()) {
                log.error("Get template config failed: targetType={}, targetId={}, errorCode={}, errorMsg={}",
                        targetType, targetId, result != null ? result.getCode() : "NULL",
                        result != null ? result.getMessage() : "NULL");
                throw EcoMarketException.build(BizExceptionCodeEnum.configNotFound, "查询模板配置失败");
            }

            return result.getData();
        } catch (EcoMarketException e) {
            throw e;
        } catch (Exception e) {
            log.error("Get template config error: targetType={}, targetId={}", targetType, targetId, e);
            throw EcoMarketException.build(BizExceptionCodeEnum.configNotFound, "查询模板配置异常: " + e.getMessage());
        }
    }

    @Override
    public Long templateEnableOrUpdate(TemplateEnableOrUpdateDto templateEnableOrUpdateDto) {
        try {
            log.info("Agent RPC enable/update template: templateEnableOrUpdateDto={}", templateEnableOrUpdateDto);
            if (templateEnableOrUpdateDto == null) {
                throw new IllegalArgumentException("Template enable or update DTO cannot be empty");
            }

            var result = agentRpcService.templateEnableOrUpdate(templateEnableOrUpdateDto);
            if (result == null || !result.isSuccess()) {
                log.error("Enable/update template failed: errorCode={}, errorMsg={}",
                        result != null ? result.getCode() : "NULL", result != null ? result.getMessage() : "NULL");
                throw EcoMarketException.build(BizExceptionCodeEnum.configNotFound, "启用或更新模板失败");
            }

            return result.getData();
        } catch (EcoMarketException e) {
            throw e;
        } catch (Exception e) {
            log.error("Enable/update template error", e);
            throw EcoMarketException.build(BizExceptionCodeEnum.configNotFound, "启用或更新模板异常: " + e.getMessage());
        }
    }

    @Override
    public Void disableTemplate(TargetTypeEnum targetType, Long targetId) {
        try {
            log.info("Agent RPC disable template: targetType={}, targetId={}", targetType, targetId);
            if (targetType == null || targetId == null) {
                throw new IllegalArgumentException("Target type or target ID cannot be empty");
            }

            var result = agentRpcService.disableTemplate(targetType, targetId);
            if (result == null || !result.isSuccess()) {
                log.error("Disable template failed: targetType={}, targetId={}, errorCode={}, errorMsg={}",
                        targetType, targetId, result != null ? result.getCode() : "NULL",
                        result != null ? result.getMessage() : "NULL");
                throw EcoMarketException.build(BizExceptionCodeEnum.configNotFound, "禁用模板失败");
            }

            return null;
        } catch (EcoMarketException e) {
            throw e;
        } catch (Exception e) {
            log.error("Disable template error: targetType={}, targetId={}", targetType, targetId, e);
            throw EcoMarketException.build(BizExceptionCodeEnum.configNotFound, "禁用模板异常: " + e.getMessage());
        }
    }

    @Override
    public Long deployOfficialMcp(McpDto mcpDto) {
        log.info("MCP API deploy MCP: mcpDto={}", mcpDto);
        if (mcpDto == null) {
            log.error("Deploy MCP failed: MCP config required");
            throw EcoMarketException.build(BizExceptionCodeEnum.fieldRequiredButEmpty, "MCP配置");
        }

        var result = mcpApiService.deployOfficialMcp(mcpDto);
        if (result == null) {
            log.error("Deploy MCP failed: mcpDto={}", mcpDto);
            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketDeployMcpFailed);
        }

        return result;
    }

    @Override
    public Void stopOfficialMcp(Long targetId) {
        log.info("MCP API stop MCP: targetId={}", targetId);
        if (targetId == null) {
            log.error("Stop MCP failed: MCP ID required");
            throw EcoMarketException.build(BizExceptionCodeEnum.ecoMarketStopMcpFailed);
        }

        mcpApiService.stopOfficialMcp(targetId);
        return null;
    }
}
