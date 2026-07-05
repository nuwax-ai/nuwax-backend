package com.xspaceagi.agent.core.domain.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.google.common.collect.Lists;
import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.*;
import com.xspaceagi.agent.core.adapter.repository.CopyIndexRecordRepository;
import com.xspaceagi.agent.core.adapter.repository.WorkflowConfigRepository;
import com.xspaceagi.agent.core.adapter.repository.WorkflowNodeConfigRepository;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.adapter.repository.entity.WorkflowConfig;
import com.xspaceagi.agent.core.adapter.repository.entity.WorkflowNodeConfig;
import com.xspaceagi.agent.core.domain.service.PublishDomainService;
import com.xspaceagi.agent.core.domain.service.WorkflowDomainService;
import com.xspaceagi.agent.core.spec.enums.DataTypeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.utils.I18nUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class WorkflowDomainServiceImpl implements WorkflowDomainService {

    @Resource
    private WorkflowConfigRepository workflowConfigRepository;

    @Resource
    private WorkflowNodeConfigRepository workflowNodeConfigRepository;

    @Resource
    private PublishDomainService publishDomainService;

    @Resource
    private CopyIndexRecordRepository copyIndexRecordRepository;

    @Override
    @DSTransactional
    public void add(WorkflowConfig workflowConfig) {
        Assert.notNull(workflowConfig, "workflowConfig must be non-null");
        Assert.notNull(workflowConfig.getSpaceId(), "spaceId must be non-null");
        Assert.notNull(workflowConfig.getCreatorId(), "creatorId must be non-null");
        Assert.notNull(workflowConfig.getName(), "name must be non-null");
        workflowConfigRepository.save(workflowConfig);
        NodeConfigDto startNodeConfigDto = new NodeConfigDto();
        startNodeConfigDto.setExtension(Map.of("x", 30, "y", 70));
        Arg arg = Arg.builder().name("input").dataType(DataTypeEnum.String).enable(true).description("input message").build();
        startNodeConfigDto.setInputArgs(List.of(arg));

        EndNodeConfigDto endNodeConfigDto = new EndNodeConfigDto();
        endNodeConfigDto.setExtension(Map.of("x", 1000, "y", 70));
        endNodeConfigDto.setReturnType(EndNodeConfigDto.ReturnType.VARIABLE);
        WorkflowNodeConfig endNode = WorkflowNodeConfig.builder()
                .workflowId(workflowConfig.getId())
                .name(I18nUtil.systemMessage("Backend.WorkflowNode.End.name"))
                .description(I18nUtil.systemMessage("Backend.WorkflowNode.End.description"))
                .type(WorkflowNodeConfig.NodeType.End)
                .config(JSON.toJSONString(endNodeConfigDto))
                .build();
        workflowNodeConfigRepository.save(endNode);

        //创建开始和结束节点
        WorkflowNodeConfig startNode = WorkflowNodeConfig.builder()
                .workflowId(workflowConfig.getId())
                .name(I18nUtil.systemMessage("Backend.WorkflowNode.Start.name"))
                .description(I18nUtil.systemMessage("Backend.WorkflowNode.Start.description"))
                .type(WorkflowNodeConfig.NodeType.Start)
                .config(JSON.toJSONString(startNodeConfigDto))
                .nextNodeIds(Lists.newArrayList(endNode.getId()))
                .build();
        workflowNodeConfigRepository.save(startNode);

        //设置工作流起始节点
        workflowConfig.setStartNodeId(startNode.getId());
        //设置工作流结束节点
        workflowConfig.setEndNodeId(endNode.getId());
        workflowConfigRepository.updateById(workflowConfig);
    }

    @Override
    public void delete(Long workflowId) {
        workflowConfigRepository.removeById(workflowId);
        workflowNodeConfigRepository.remove(new QueryWrapper<>(WorkflowNodeConfig.builder().workflowId(workflowId).build()));
        publishDomainService.deleteByTargetId(Published.TargetType.Workflow, workflowId);
        publishDomainService.deletePublishedApply(Published.TargetType.Workflow, workflowId);
    }

    @Override
    public void deleteBySpaceId(Long spaceId) {
        queryListBySpaceId(spaceId).forEach(workflowConfig -> {
            delete(workflowConfig.getId());
        });
    }

    @Override
    public void update(WorkflowConfig workflowConfig) {
        Assert.notNull(workflowConfig, "workflowConfig must be non-null");
        Assert.notNull(workflowConfig.getId(), "id must be non-null");
        if (workflowConfig.getIcon() != null && workflowConfig.getIcon().contains("api/logo")) {
            workflowConfig.setIcon(null);
        }
        workflowConfigRepository.updateById(workflowConfig);
    }

    @Override
    public WorkflowConfig queryById(Long workflowId) {
        return workflowConfigRepository.getById(workflowId);
    }

    @Override
    public List<WorkflowConfig> queryListByIds(List<Long> workflowIds) {
        if (workflowIds.isEmpty()) {
            return new ArrayList<>();
        }
        LambdaQueryWrapper<WorkflowConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(WorkflowConfig::getId, workflowIds);
        return workflowConfigRepository.list(queryWrapper);
    }

    @Override
    public List<WorkflowConfig> queryListBySpaceId(Long spaceId) {
        LambdaQueryWrapper<WorkflowConfig> queryWrapper = new LambdaQueryWrapper<>(WorkflowConfig.builder().spaceId(spaceId).type(WorkflowConfigDto.Type.Workflow.name()).build());
        return workflowConfigRepository.list(queryWrapper);
    }

    @Override
    public void transfer(Long workflowId, Long targetSpaceId) {
        Assert.notNull(workflowId, "workflowId must be non-null");
        Assert.notNull(targetSpaceId, "targetSpaceId must be non-null");
        WorkflowConfig workflowConfig = workflowConfigRepository.getById(workflowId);
        workflowConfig.setSpaceId(targetSpaceId);
        workflowConfigRepository.updateById(workflowConfig);
    }

    @Override
    @DSTransactional
    public Long copy(Long userId, Long workflowId) {
        WorkflowConfig workflowConfig = workflowConfigRepository.getById(workflowId);
        if (workflowConfig == null) {
            throw BizException.of(BizExceptionCodeEnum.agentIdInvalid);
        }

        List<WorkflowNodeConfig> workflowNodeConfigs = queryNodeConfigListByWorkflowId(workflowId);
        return copy(userId, workflowConfig, workflowNodeConfigs, workflowConfig.getSpaceId());
    }

    @Override
    @DSTransactional
    public Long copy(Long userId, WorkflowConfig workflowConfig, List<WorkflowNodeConfig> workflowNodeConfigs, Long targetSpaceId) {
        WorkflowConfig newWorkflowConfig = WorkflowConfig.builder()
                .spaceId(targetSpaceId)
                .creatorId(userId)
                .type(workflowConfig.getType())
                .name(workflowConfig.getName())
                .description(workflowConfig.getDescription())
                .icon(workflowConfig.getIcon())
                .publishStatus(Published.PublishStatus.Developing)
                .build();
        if (targetSpaceId != -1) {
            String newName = copyIndexRecordRepository.newCopyName("workflow", workflowConfig.getSpaceId(), workflowConfig.getName());
            newWorkflowConfig.setName(newName);
        }
        workflowConfigRepository.save(newWorkflowConfig);
        //workflowNodeConfigs转map
        Map<Long, WorkflowNodeConfig> newWorkflowNodeConfigMap = new HashMap<>();

        //将workflowNodeConfigs新增到新的工作流
        workflowNodeConfigs.forEach(workflowNodeConfig -> {
            WorkflowNodeConfig newWorkflowNodeConfig = WorkflowNodeConfig.builder()
                    .workflowId(newWorkflowConfig.getId())
                    .name(workflowNodeConfig.getName())
                    .description(workflowNodeConfig.getDescription())
                    .icon(workflowNodeConfig.getIcon())
                    .type(workflowNodeConfig.getType())
                    .config(workflowNodeConfig.getConfig())
                    .build();
            workflowNodeConfigRepository.save(newWorkflowNodeConfig);
            newWorkflowNodeConfigMap.put(workflowNodeConfig.getId(), newWorkflowNodeConfig);
        });
        //补充新工作流各个节点下级节点信息
        workflowNodeConfigs.forEach(workflowNodeConfig -> {
            WorkflowNodeConfig newWorkflowNodeConfig = newWorkflowNodeConfigMap.get(workflowNodeConfig.getId());
            newWorkflowNodeConfig.setNextNodeIds(new ArrayList<>());
            //补充下级节点
            if (workflowNodeConfig.getNextNodeIds() != null && !workflowNodeConfig.getNextNodeIds().isEmpty()) {
                workflowNodeConfig.getNextNodeIds().forEach(nextNodeId -> {
                    WorkflowNodeConfig newNextWorkflowNodeConfig = newWorkflowNodeConfigMap.get(nextNodeId);
                    if (newNextWorkflowNodeConfig != null) {
                        newWorkflowNodeConfig.getNextNodeIds().add(newNextWorkflowNodeConfig.getId());
                    }
                });
            }
            //补充循环内部节点
            if (workflowNodeConfig.getLoopNodeId() != null) {
                WorkflowNodeConfig newWorkflowNodeConfig0 = newWorkflowNodeConfigMap.get(workflowNodeConfig.getLoopNodeId());
                if (newWorkflowNodeConfig0 != null) {
                    newWorkflowNodeConfig.setLoopNodeId(newWorkflowNodeConfig0.getId());
                }
            }

            if (newWorkflowNodeConfig.getType() == WorkflowNodeConfig.NodeType.Loop) {
                WorkflowNodeConfig workflowNodeConfig1 = newWorkflowNodeConfigMap.get(workflowNodeConfig.getInnerStartNodeId());
                if (workflowNodeConfig1 != null) {
                    newWorkflowNodeConfig.setInnerStartNodeId(workflowNodeConfig1.getId());
                }
                workflowNodeConfig1 = newWorkflowNodeConfigMap.get(workflowNodeConfig.getInnerEndNodeId());
                if (workflowNodeConfig1 != null) {
                    newWorkflowNodeConfig.setInnerEndNodeId(workflowNodeConfig1.getId());
                }
            }

            if (newWorkflowNodeConfig.getType() == WorkflowNodeConfig.NodeType.QA) {
                QaNodeConfigDto qaNodeConfigDto = JSON.parseObject(workflowNodeConfig.getConfig(), QaNodeConfigDto.class);
                if (qaNodeConfigDto != null && qaNodeConfigDto.getOptions() != null) {
                    qaNodeConfigDto.getOptions().forEach(option -> {
                        List<Long> newNextNodeIds = new ArrayList<>();
                        resetNextNodeIds(option.getNextNodeIds(), newWorkflowNodeConfigMap, newNextNodeIds);
                        option.setNextNodeIds(newNextNodeIds);
                    });
                    newWorkflowNodeConfig.setConfig(JSON.toJSONString(qaNodeConfigDto));
                }
            }

            if (newWorkflowNodeConfig.getType() == WorkflowNodeConfig.NodeType.IntentRecognition) {
                IntentRecognitionNodeConfigDto nodeConfigDto = JSON.parseObject(workflowNodeConfig.getConfig(), IntentRecognitionNodeConfigDto.class);
                if (nodeConfigDto != null && nodeConfigDto.getIntentConfigs() != null) {
                    nodeConfigDto.getIntentConfigs().forEach(intent -> {
                        List<Long> newNextNodeIds = new ArrayList<>();
                        resetNextNodeIds(intent.getNextNodeIds(), newWorkflowNodeConfigMap, newNextNodeIds);
                        intent.setNextNodeIds(newNextNodeIds);
                    });
                    newWorkflowNodeConfig.setConfig(JSON.toJSONString(nodeConfigDto));
                }
            }

            if (newWorkflowNodeConfig.getType() == WorkflowNodeConfig.NodeType.Condition) {
                ConditionNodeConfigDto conditionNodeConfigDto = JSON.parseObject(workflowNodeConfig.getConfig(), ConditionNodeConfigDto.class);
                if (conditionNodeConfigDto != null && conditionNodeConfigDto.getConditionBranchConfigs() != null) {
                    conditionNodeConfigDto.getConditionBranchConfigs().forEach(condition -> {
                        List<Long> newNextNodeIds = new ArrayList<>();
                        resetNextNodeIds(condition.getNextNodeIds(), newWorkflowNodeConfigMap, newNextNodeIds);
                        condition.setNextNodeIds(newNextNodeIds);
                    });
                    newWorkflowNodeConfig.setConfig(JSON.toJSONString(conditionNodeConfigDto));
                }
            }

            //更新节点参数绑定
            JSONObject jsonObject = JSON.parseObject(newWorkflowNodeConfig.getConfig());
            replaceOldNodeId(jsonObject, newWorkflowNodeConfigMap);
            //异常处理节点连线处理
            if (jsonObject != null && jsonObject.getJSONObject("exceptionHandleConfig") != null) {
                JSONObject exceptionHandleConfig = jsonObject.getJSONObject("exceptionHandleConfig");
                if (exceptionHandleConfig.getJSONArray("exceptionHandleNodeIds") != null) {
                    List<Long> newExNodeIds = new ArrayList<>();
                    exceptionHandleConfig.getJSONArray("exceptionHandleNodeIds").forEach(exceptionHandleNodeId -> {
                        WorkflowNodeConfig newWorkflowNodeConfig0 = newWorkflowNodeConfigMap.get(Long.parseLong(exceptionHandleNodeId.toString()));
                        if (newWorkflowNodeConfig0 != null) {
                            newExNodeIds.add(newWorkflowNodeConfig0.getId());
                        }
                    });
                    exceptionHandleConfig.getJSONArray("exceptionHandleNodeIds").clear();
                    exceptionHandleConfig.getJSONArray("exceptionHandleNodeIds").addAll(newExNodeIds);
                }
            }

            newWorkflowNodeConfig.setConfig(jsonObject.toJSONString());

            //设置开始节点和结束节点
            if (newWorkflowNodeConfig.getType() == WorkflowNodeConfig.NodeType.Start) {
                newWorkflowConfig.setStartNodeId(newWorkflowNodeConfig.getId());
            }
            if (newWorkflowNodeConfig.getType() == WorkflowNodeConfig.NodeType.End) {
                newWorkflowConfig.setEndNodeId(newWorkflowNodeConfig.getId());
            }
            workflowNodeConfigRepository.updateById(newWorkflowNodeConfig);
        });

        workflowConfigRepository.updateById(newWorkflowConfig);
        return newWorkflowConfig.getId();
    }

    private void resetNextNodeIds(List<Long> nextNodeIds, Map<Long, WorkflowNodeConfig> newWorkflowNodeConfigMap, List<Long> newNextNodeIds) {
        if (CollectionUtils.isEmpty(nextNodeIds)) {
            return;
        }
        nextNodeIds.forEach(nextNodeId -> {
            WorkflowNodeConfig newNextWorkflowNodeConfig = newWorkflowNodeConfigMap.get(nextNodeId);
            if (newNextWorkflowNodeConfig != null) {
                newNextNodeIds.add(newNextWorkflowNodeConfig.getId());
            }
        });
    }

    @Override
    public List<WorkflowNodeConfig> queryNodeConfigListByWorkflowId(Long workflowId) {
        LambdaQueryWrapper<WorkflowNodeConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WorkflowNodeConfig::getWorkflowId, workflowId);
        return workflowNodeConfigRepository.list(queryWrapper);
    }

    @Override
    @DSTransactional
    public void addWorkflowNode(WorkflowNodeConfig workflowNodeConfig) {
        workflowNodeConfigRepository.save(workflowNodeConfig);
        if (workflowNodeConfig.getType() == WorkflowNodeConfig.NodeType.Loop) {
            checkAndUpdateLoopStartAndEndNodes(workflowNodeConfig, new HashMap<>());
        }
    }

    @Override
    public void updateWorkflowNodeConfig(WorkflowNodeConfig workflowNodeConfig) {
        Assert.notNull(workflowNodeConfig.getId(), "id must be non-null");
        workflowNodeConfigRepository.updateById(workflowNodeConfig);
    }

    @Override
    @DSTransactional
    public void deleteWorkflowNode(Long id) {
        WorkflowNodeConfig workflowNodeConfig = workflowNodeConfigRepository.getById(id);
        if (workflowNodeConfig == null) {
            return;
        }
        workflowNodeConfigRepository.removeById(id);

        // 删除循环节点
        if (workflowNodeConfig.getType() == WorkflowNodeConfig.NodeType.Loop) {
            workflowNodeConfigRepository.remove(new LambdaQueryWrapper<WorkflowNodeConfig>(WorkflowNodeConfig.builder().loopNodeId(id).build()));
        }

        List<WorkflowNodeConfig> workflowNodeConfigs = queryNodeConfigListByWorkflowId(workflowNodeConfig.getWorkflowId());
        //workflowNodeConfigs更新下级节点信息
        workflowNodeConfigs.forEach(workflowNodeConfig1 -> {
            //作为循环节点的开始或结束节点，需要更新循环节点内部起始和结束ID
            if (workflowNodeConfig.getLoopNodeId() != null) {
                if (workflowNodeConfig1.getType() == WorkflowNodeConfig.NodeType.Loop && workflowNodeConfig.getLoopNodeId().equals(workflowNodeConfig1.getId())) {
                    if (workflowNodeConfig1.getInnerStartNodeId() != null && workflowNodeConfig1.getInnerStartNodeId().equals(workflowNodeConfig.getId())) {
                        LambdaUpdateWrapper<WorkflowNodeConfig> lambdaUpdateWrapper = new LambdaUpdateWrapper<WorkflowNodeConfig>().set(WorkflowNodeConfig::getInnerStartNodeId, -1).eq(WorkflowNodeConfig::getId, workflowNodeConfig1.getId());
                        workflowNodeConfigRepository.update(lambdaUpdateWrapper);
                        log.info("循环开始节点更新为空 {}", workflowNodeConfig1.getInnerStartNodeId());
                    }
                    if (workflowNodeConfig1.getInnerEndNodeId() != null && workflowNodeConfig1.getInnerEndNodeId().equals(workflowNodeConfig.getId())) {
                        LambdaUpdateWrapper<WorkflowNodeConfig> lambdaUpdateWrapper = new LambdaUpdateWrapper<WorkflowNodeConfig>().set(WorkflowNodeConfig::getInnerEndNodeId, -1).eq(WorkflowNodeConfig::getId, workflowNodeConfig1.getId());
                        workflowNodeConfigRepository.update(lambdaUpdateWrapper);
                        log.info("循环结束节点更新为空 {}", workflowNodeConfig1.getInnerEndNodeId());
                    }
                }
            }

            if (workflowNodeConfig1.getNextNodeIds() != null && !workflowNodeConfig1.getNextNodeIds().isEmpty()) {
                if (workflowNodeConfig1.getNextNodeIds().removeIf(nextNodeId -> nextNodeId.equals(id))) {
                    workflowNodeConfigRepository.updateById(workflowNodeConfig1);
                }
            }
            if (workflowNodeConfig1.getType() == WorkflowNodeConfig.NodeType.Condition) {
                ConditionNodeConfigDto conditionNodeConfigDto = JSON.parseObject(workflowNodeConfig1.getConfig(), ConditionNodeConfigDto.class);
                if (conditionNodeConfigDto != null && !CollectionUtils.isEmpty(conditionNodeConfigDto.getConditionBranchConfigs())) {
                    conditionNodeConfigDto.getConditionBranchConfigs().forEach(condition -> {
                        if (!CollectionUtils.isEmpty(condition.getNextNodeIds())) {
                            if (condition.getNextNodeIds().removeIf(nextNodeId -> nextNodeId.equals(id))) {
                                workflowNodeConfig1.setConfig(JSON.toJSONString(conditionNodeConfigDto));
                                workflowNodeConfigRepository.updateById(workflowNodeConfig1);
                            }
                        }
                    });
                }
            }
            //意图识别
            if (workflowNodeConfig1.getType() == WorkflowNodeConfig.NodeType.IntentRecognition) {
                IntentRecognitionNodeConfigDto nodeConfigDto = JSON.parseObject(workflowNodeConfig1.getConfig(), IntentRecognitionNodeConfigDto.class);
                if (nodeConfigDto != null && !CollectionUtils.isEmpty(nodeConfigDto.getIntentConfigs())) {
                    nodeConfigDto.getIntentConfigs().forEach(intent -> {
                        if (!CollectionUtils.isEmpty(intent.getNextNodeIds())) {
                            if (intent.getNextNodeIds().removeIf(nextNodeId -> nextNodeId.equals(id))) {
                                workflowNodeConfig1.setConfig(JSON.toJSONString(nodeConfigDto));
                                workflowNodeConfigRepository.updateById(workflowNodeConfig1);
                            }
                        }
                    });
                }
            }
            //问答
            if (workflowNodeConfig1.getType() == WorkflowNodeConfig.NodeType.QA) {
                QaNodeConfigDto qaNodeConfigDto = JSON.parseObject(workflowNodeConfig1.getConfig(), QaNodeConfigDto.class);
                if (qaNodeConfigDto != null && !CollectionUtils.isEmpty(qaNodeConfigDto.getOptions())) {
                    qaNodeConfigDto.getOptions().forEach(option -> {
                        if (!CollectionUtils.isEmpty(option.getNextNodeIds())) {
                            if (option.getNextNodeIds().removeIf(nextNodeId -> nextNodeId.equals(id))) {
                                workflowNodeConfig1.setConfig(JSON.toJSONString(qaNodeConfigDto));
                                workflowNodeConfigRepository.updateById(workflowNodeConfig1);
                            }
                        }
                    });
                }
            }
        });
    }

    @Override
    public WorkflowNodeConfig queryWorkflowNode(Long id) {
        return workflowNodeConfigRepository.getById(id);
    }

    @Override
    @DSTransactional
    public void restoreWorkflow(WorkflowConfig workflowConfig, List<WorkflowNodeConfig> workflowNodeConfigs) {
        WorkflowConfig workflowConfig1 = queryById(workflowConfig.getId());
        //获取开始节点
        WorkflowNodeConfig startNode = workflowNodeConfigs.stream().filter(workflowNodeConfig -> workflowNodeConfig.getType() == WorkflowNodeConfig.NodeType.Start).findFirst().get();
        //结束节点
        WorkflowNodeConfig endNode = workflowNodeConfigs.stream().filter(workflowNodeConfig -> workflowNodeConfig.getType() == WorkflowNodeConfig.NodeType.End).findFirst().get();
        workflowConfig.setStartNodeId(startNode.getId());
        workflowConfig.setEndNodeId(endNode.getId());
        workflowConfig.setName(workflowConfig1.getName());
        workflowConfig.setDescription(workflowConfig1.getDescription());
        workflowConfig.setIcon(workflowConfig1.getIcon());
        workflowConfig.setPublishStatus(workflowConfig1.getPublishStatus());
        workflowConfig.setModified(new Date());
        workflowConfig.setExt(workflowConfig1.getExt());
        workflowConfig.setCreatorId(workflowConfig1.getCreatorId());

        workflowConfigRepository.removeById(workflowConfig.getId());
        LambdaQueryWrapper<WorkflowNodeConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WorkflowNodeConfig::getWorkflowId, workflowConfig.getId());
        workflowNodeConfigRepository.remove(queryWrapper);
        workflowConfigRepository.save(workflowConfig);
        workflowNodeConfigRepository.saveBatch(workflowNodeConfigs);
    }

    @Override
    @DSTransactional
    public boolean checkAndUpdateLoopStartAndEndNodes(WorkflowNodeConfig workflowNodeConfig, Map<Long, WorkflowNodeConfig> workflowNodeConfigMap) {
        Long startNodeId = workflowNodeConfig.getInnerStartNodeId();
        Long endNodeId = workflowNodeConfig.getInnerEndNodeId();
        if (startNodeId != null) {
            WorkflowNodeConfig startNode = workflowNodeConfigMap.get(startNodeId);
            if (startNode != null && startNode.getType() == WorkflowNodeConfig.NodeType.LoopStart) {
                //有start，必有end，所以后续不再对end做验证
                return false;
            }
        }
        WorkflowNodeConfig endNode = null;
        if (endNodeId != null && endNodeId != -1) {
            endNode = workflowNodeConfigMap.get(endNodeId);
        }
        JSONObject jsonObject = JSON.parseObject(workflowNodeConfig.getConfig());
        JSONObject extension = jsonObject.getJSONObject("extension");
        int width = 600;
        int height = 240;
        int x = 160;
        int y = 160;
        if (extension != null) {
            x = extension.getIntValue("x", 160);
            y = extension.getIntValue("y", 160);
            width = extension.getIntValue("width", 600);
            height = extension.getIntValue("height", 240);
            width = width + 200;
            extension.put("width", width);
            extension.put("height", height);
            x = x - 100;
            extension.put("x", x);
        }

        workflowNodeConfig.setConfig(jsonObject.toJSONString());
        JSONObject innerStartNodeConfig = new JSONObject();
        JSONObject ext = new JSONObject();
        ext.put("x", x + 40);
        ext.put("y", y + height / 2 - 20);
        innerStartNodeConfig.put("extension", ext);

        JSONObject innerEndNodeConfig = new JSONObject();
        ext = new JSONObject();
        ext.put("x", x + 600);
        ext.put("y", y + height / 2 - 20);
        innerEndNodeConfig.put("extension", ext);

        WorkflowNodeConfig loopEndNodeConfig = new WorkflowNodeConfig();
        loopEndNodeConfig.setWorkflowId(workflowNodeConfig.getWorkflowId());
        loopEndNodeConfig.setName(WorkflowNodeConfig.NodeType.LoopEnd.getName());
        loopEndNodeConfig.setType(WorkflowNodeConfig.NodeType.LoopEnd);
        loopEndNodeConfig.setIcon("");
        loopEndNodeConfig.setDescription(WorkflowNodeConfig.NodeType.LoopEnd.getDescription());
        loopEndNodeConfig.setConfig(innerEndNodeConfig.toJSONString());
        loopEndNodeConfig.setLoopNodeId(workflowNodeConfig.getId());
        workflowNodeConfigRepository.save(loopEndNodeConfig);

        WorkflowNodeConfig loopStartNodeConfig = new WorkflowNodeConfig();
        loopStartNodeConfig.setWorkflowId(workflowNodeConfig.getWorkflowId());
        loopStartNodeConfig.setName(WorkflowNodeConfig.NodeType.LoopStart.getName());
        loopStartNodeConfig.setType(WorkflowNodeConfig.NodeType.LoopStart);
        loopStartNodeConfig.setIcon("");
        loopStartNodeConfig.setDescription(WorkflowNodeConfig.NodeType.LoopStart.getDescription());
        loopStartNodeConfig.setConfig(innerStartNodeConfig.toJSONString());
        loopStartNodeConfig.setLoopNodeId(workflowNodeConfig.getId());
        if (startNodeId != null && startNodeId != -1) {
            loopStartNodeConfig.setNextNodeIds(Arrays.asList(startNodeId));
        } else if (endNodeId == null || endNodeId == -1 || endNode == null) {
            loopStartNodeConfig.setNextNodeIds(Arrays.asList(loopEndNodeConfig.getId()));
        }
        workflowNodeConfigRepository.save(loopStartNodeConfig);
        if (endNode != null) {
            endNode.setNextNodeIds(Arrays.asList(loopEndNodeConfig.getId()));
            workflowNodeConfigRepository.updateById(endNode);
        }

        workflowNodeConfig.setInnerStartNodeId(loopStartNodeConfig.getId());
        workflowNodeConfig.setInnerEndNodeId(loopEndNodeConfig.getId());
        updateWorkflowNodeConfig(workflowNodeConfig);
        return true;
    }

    private static void replaceOldNodeId(Object obj, Map<Long, WorkflowNodeConfig> workflowNodeConfigMap) {
        if (obj == null) {
            return;
        }
        JSONObject jsonObject;
        if (obj instanceof JSONObject) {
            jsonObject = (JSONObject) obj;
        } else {
            return;
        }
        //在jsonObject中查找包含argNames字段的子对象
        jsonObject.keySet().forEach(key -> {
            if (jsonObject.isArray(key)) {
                JSONArray array = jsonObject.getJSONArray(key);
                for (int i = 0; i < array.size(); i++) {
                    replaceOldNodeId(array.get(i), workflowNodeConfigMap);
                }
            } else {
                if (!(jsonObject.get(key) instanceof JSONObject)) {
                    return;
                }
                JSONObject object = jsonObject.getJSONObject(key);
                replaceOldNodeId(object, workflowNodeConfigMap);
            }
        });

        String bindValueType = jsonObject.getString("bindValueType");
        if (StringUtils.isNotBlank(bindValueType) && bindValueType.equals("Reference")) {
            if (jsonObject.containsKey("bindValue")) {
                String bindValue = jsonObject.getString("bindValue");
                if (StringUtils.isNotBlank(bindValue)) {
                    Long aLong = extractLeadingNumber(bindValue);
                    if (aLong != null) {
                        WorkflowNodeConfig workflowNodeConfig = workflowNodeConfigMap.get(aLong);
                        if (workflowNodeConfig != null) {
                            bindValue = bindValue.replace(aLong.toString(), workflowNodeConfig.getId().toString());
                            jsonObject.put("bindValue", bindValue);
                        }
                    }
                }
            }
        }
        if (StringUtils.isNotBlank(bindValueType)) {
            String keyVal = jsonObject.getString("key");
            if (StringUtils.isNotBlank(keyVal)) {
                Long aLong = extractLeadingNumber(keyVal);
                if (aLong != null) {
                    WorkflowNodeConfig workflowNodeConfig = workflowNodeConfigMap.get(aLong);
                    if (workflowNodeConfig != null) {
                        keyVal = keyVal.replace(aLong.toString(), workflowNodeConfig.getId().toString());
                        jsonObject.put("key", keyVal);
                    }
                }
            }
        }

    }

    private static Long extractLeadingNumber(String key) {
        Pattern pattern = Pattern.compile("^\\d+");
        Matcher matcher = pattern.matcher(key);
        if (matcher.find()) {
            return Long.parseLong(matcher.group());
        }
        return null;
    }
}
