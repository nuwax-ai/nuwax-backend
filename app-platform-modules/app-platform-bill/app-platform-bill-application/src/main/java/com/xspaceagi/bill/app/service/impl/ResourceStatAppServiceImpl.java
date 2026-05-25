package com.xspaceagi.bill.app.service.impl;

import com.xspaceagi.agent.core.sdk.IAgentRpcService;
import com.xspaceagi.agent.core.sdk.IModelRpcService;
import com.xspaceagi.agent.core.sdk.dto.AgentInfoDto;
import com.xspaceagi.agent.core.sdk.dto.ModelInfoDto;
import com.xspaceagi.agent.core.sdk.dto.PluginInfoDto;
import com.xspaceagi.agent.core.sdk.dto.WorkflowInfoDto;
import com.xspaceagi.bill.app.service.ResourceStatAppService;
import com.xspaceagi.bill.infra.dao.entity.BillResourceStat;
import com.xspaceagi.bill.infra.dao.mapper.BillResourceStatMapper;
import com.xspaceagi.bill.infra.dao.service.IBillResourceStatService;
import com.xspaceagi.bill.sdk.dto.ResourceStatDTO;
import com.xspaceagi.bill.sdk.dto.ResourceStatPageDTO;
import com.xspaceagi.bill.sdk.dto.ResourceStatSummaryDTO;
import com.xspaceagi.bill.spec.enums.ResourceStatTypeEnum;
import com.xspaceagi.system.sdk.server.IUserRpcService;
import com.xspaceagi.system.spec.common.UserContext;
import jakarta.annotation.Resource;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ResourceStatAppServiceImpl implements ResourceStatAppService {

    @Resource
    private IBillResourceStatService billResourceStatService;

    @Resource
    private BillResourceStatMapper billResourceStatMapper;

    @Resource
    private IUserRpcService iUserRpcService;

    @Resource
    private IModelRpcService iModelRpcService;

    @Resource
    private IAgentRpcService iAgentRpcService;

    @Override
    public ResourceStatPageDTO queryResourceStats(Long tenantId, Long userId, String type,
                                                  String targetType, Long targetId,
                                                  String dtStart, String dtEnd,
                                                  Integer pageNum, Integer pageSize) {
        int offset = (pageNum - 1) * pageSize;
        List<ResourceStatDTO> resourceStatDTOS = billResourceStatService.queryStats(tenantId, userId, type, targetType, targetId, dtStart, dtEnd, offset, pageSize)
                .stream().map(this::convertToDTO).collect(Collectors.toList());
        if (!resourceStatDTOS.isEmpty()) {
            List<Long> userIds = resourceStatDTOS.stream().map(ResourceStatDTO::getUserId).distinct().toList();
            Map<Long, UserContext> userMap = iUserRpcService.queryUserListByIds(userIds)
                    .stream().collect(Collectors.toMap(UserContext::getUserId, user -> user, (a, b) -> a));
            List<Long> modelIds = resourceStatDTOS.stream().filter(resourceStatDTO -> resourceStatDTO.getTargetType() != null && resourceStatDTO.getTargetType().equals("Model")).map(ResourceStatDTO::getTargetId).distinct().toList();
            Map<Long, ModelInfoDto> modelInfoDtoMap = iModelRpcService.getModelInfoList(modelIds).stream().collect(Collectors.toMap(ModelInfoDto::getId, modelInfoDto -> modelInfoDto, (a, b) -> a));

            List<Long> agentIds = resourceStatDTOS.stream().filter(resourceStatDTO -> resourceStatDTO.getTargetType() != null && resourceStatDTO.getTargetType().equals("Agent")).map(ResourceStatDTO::getTargetId).distinct().toList();
            Map<Long, AgentInfoDto> agentInfoDtoMap = iAgentRpcService.queryAgentInfoList(agentIds).getData().stream().collect(Collectors.toMap(AgentInfoDto::getId, agentInfoDto -> agentInfoDto, (a, b) -> a));

            resourceStatDTOS.forEach(resourceStatDTO -> {
                UserContext userContext = userMap.get(resourceStatDTO.getUserId());
                if (userContext != null) {
                    resourceStatDTO.setPhone(userContext.getPhone());
                    resourceStatDTO.setEmail(userContext.getEmail());
                    resourceStatDTO.setUserName(userContext.getUserName());
                    resourceStatDTO.setNickName(userContext.getNickName());
                }
                if (resourceStatDTO.getTargetType() != null && resourceStatDTO.getTargetType().equals("Model")) {
                    ModelInfoDto modelInfoDto = modelInfoDtoMap.get(resourceStatDTO.getTargetId());
                    if (modelInfoDto != null) {
                        resourceStatDTO.setTargetName(modelInfoDto.getName());
                    }
                }

                if (resourceStatDTO.getTargetType() != null && resourceStatDTO.getTargetType().equals("Agent")) {
                    AgentInfoDto agentInfoDto = agentInfoDtoMap.get(resourceStatDTO.getTargetId());
                    if (agentInfoDto != null) {
                        resourceStatDTO.setTargetName(agentInfoDto.getName());
                    }
                }

                if (resourceStatDTO.getTargetType() != null && resourceStatDTO.getTargetType().equals("Plugin")) {
                    PluginInfoDto pluginInfoDto = iAgentRpcService.getPublishedPluginInfo(resourceStatDTO.getTargetId(), null).getData();
                    if (pluginInfoDto != null) {
                        resourceStatDTO.setTargetName(pluginInfoDto.getName());
                    }
                }
                if (resourceStatDTO.getTargetType() != null && resourceStatDTO.getTargetType().equals("Workflow")) {
                    WorkflowInfoDto workflowInfoDto = iAgentRpcService.getPublishedWorkflowInfo(resourceStatDTO.getTargetId(), null).getData();
                    if (workflowInfoDto != null) {
                        resourceStatDTO.setTargetName(workflowInfoDto.getName());
                    }
                }
            });
        }
        if (userId == null) {
            resourceStatDTOS.removeIf(resourceStatDTO -> resourceStatDTO.getUserId() == -1);
        }

        Long total = billResourceStatService.countStats(tenantId, userId, type, targetType, targetId, dtStart, dtEnd);

        ResourceStatPageDTO page = new ResourceStatPageDTO();
        page.setRecords(resourceStatDTOS);
        page.setTotal(total);
        page.setPageNum(pageNum);
        page.setPageSize(pageSize);
        return page;
    }

    @Override
    public ResourceStatSummaryDTO getResourceStatSummary(Long tenantId, Long userId,
                                                         String dtStart, String dtEnd) {
        List<Map<String, Object>> rows = billResourceStatMapper.selectSummary(tenantId, userId, dtStart, dtEnd);

        ResourceStatSummaryDTO dto = new ResourceStatSummaryDTO();
        for (Map<String, Object> row : rows) {
            ResourceStatSummaryDTO.StatGroup group = new ResourceStatSummaryDTO.StatGroup();
            group.setTotalInputTokens(toLong(row.get("totalInputTokens")));
            group.setTotalOutputTokens(toLong(row.get("totalOutputTokens")));
            group.setTotalCacheInputTokens(toLong(row.get("totalCacheInputTokens")));
            group.setToolCount(toLong(row.get("toolCount")));
            group.setToolCallCount(toLong(row.get("toolCallCount")));
            group.setAgentCount(toLong(row.get("agentCount")));
            group.setAgentCallCount(toLong(row.get("agentCallCount")));
            group.setModelCallCount(toLong(row.get("modelCallCount")));
            group.setFailedModelCallCount(toLong(row.get("failedModelCallCount")));
            group.setFailedToolCallCount(toLong(row.get("failedToolCallCount")));
            group.setFailedAgentCallCount(toLong(row.get("failedAgentCallCount")));
            group.setTotalCreditAmount(toBigDecimal(row.get("totalCreditAmount")));
            group.setTotalAmount(toBigDecimal(row.get("totalAmount")));

            String type = (String) row.get("type");
            if ("CONSUMPTION".equals(type)) {
                dto.setConsumption(group);
            } else if ("SALES".equals(type)) {
                dto.setSales(group);
            }
        }
        return dto;
    }

    private ResourceStatDTO convertToDTO(BillResourceStat entity) {
        ResourceStatDTO dto = new ResourceStatDTO();
        BeanUtils.copyProperties(entity, dto);
        dto.setType(ResourceStatTypeEnum.fromCode(entity.getType()));
        return dto;
    }

    private Long toLong(Object val) {
        if (val == null) return 0L;
        if (val instanceof Long) return (Long) val;
        if (val instanceof Number) return ((Number) val).longValue();
        return Long.parseLong(val.toString());
    }

    private BigDecimal toBigDecimal(Object val) {
        if (val == null) return BigDecimal.ZERO;
        if (val instanceof BigDecimal) return (BigDecimal) val;
        if (val instanceof Number) return BigDecimal.valueOf(((Number) val).doubleValue());
        return new BigDecimal(val.toString());
    }
}
