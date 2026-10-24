package com.xspaceagi.agent.web.ui.controller;

import com.xspaceagi.agent.core.adapter.application.ConversationApplicationService;
import com.xspaceagi.agent.core.adapter.application.PublishApplicationService;
import com.xspaceagi.agent.core.adapter.dto.ConversationDto;
import com.xspaceagi.agent.core.adapter.dto.PublishedDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.web.ui.controller.dto.*;
import com.xspaceagi.system.application.dto.SpaceDto;
import com.xspaceagi.system.application.dto.SpaceUserDto;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.infra.dao.entity.Space;
import com.xspaceagi.system.sdk.permission.IUserDataPermissionRpcService;
import com.xspaceagi.system.sdk.service.ScheduleTaskApiService;
import com.xspaceagi.system.sdk.service.dto.ScheduleTaskDto;
import com.xspaceagi.system.sdk.service.dto.UserAccessKeyDto;
import com.xspaceagi.system.sdk.service.dto.UserDataPermissionDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Tag(name = "沙盒Agent任务管理相关接口")
@RestController
@RequestMapping("/api/v1/4sandbox/task")
@Slf4j
public class TaskCenterForSandboxController {

    @Resource
    private SpaceApplicationService spaceApplicationService;

    @Resource
    private PublishApplicationService publishApplicationService;

    @Resource
    private ScheduleTaskApiService scheduleTaskApiService;

    @Resource
    private ConversationApplicationService conversationApplicationService;

    @Resource
    private IUserDataPermissionRpcService userDataPermissionRpcService;

    @Operation(summary = "创建定时任务")
    @RequestMapping(path = "/create", method = RequestMethod.POST)
    public ReqResult<SandboxTaskCreateResult> create(@RequestBody SandboxTaskAddDto sandboxTaskAddDto) {
        Assert.notNull(sandboxTaskAddDto.getTaskName(), "任务名称不能为空");
        Assert.notNull(sandboxTaskAddDto.getExecuteType(), "任务执行类型不能为空");
        Assert.notNull(sandboxTaskAddDto.getMessage(), "任务执行内容消息不能为空");
        Assert.notNull(sandboxTaskAddDto.getConversationId(), "会话ID不能为空");
        UserDataPermissionDto userDataPermission = userDataPermissionRpcService.getUserDataPermission(RequestContext.get().getUserId());
        if (userDataPermission.getMaxScheduledTaskCount() != null && userDataPermission.getMaxScheduledTaskCount() >= 0) {
            if (scheduleTaskApiService.countUserTotalTasks(RequestContext.get().getUserId()) >= userDataPermission.getMaxScheduledTaskCount()) {
                return ReqResult.error("你能创建的任务数量已达上限，当前最多可创建任务数为" + userDataPermission.getMaxScheduledTaskCount());
            }
        }
        Long akAgentId = resolveAkAgentId((UserAccessKeyDto) RequestContext.get().getUserAccessKey());
        if (akAgentId == null) {
            return ReqResult.error(INVALID_AK_MESSAGE);
        }
        PublishedDto publishedDto = publishApplicationService.queryPublished(Published.TargetType.Agent, akAgentId);
        if (publishedDto == null) {
            return ReqResult.error("Agent不存在或已下架");
        }
        ConversationDto conversationByCid = conversationApplicationService.getConversationByCid(sandboxTaskAddDto.getConversationId());
        if (conversationByCid == null || !conversationByCid.getAgentId().equals(akAgentId)) {
            return ReqResult.error("conversationId 错误，请从环境变量中获取");
        }
        // 空间归属解析：
        //  - 请求指定了 spaceId：只校验「当前用户是否为该空间成员」，不校验智能体所在空间。
        //    ★成员判定用 querySpaceUser（直查 space_user 行、不看空间类型），别用 queryListByUserId——
        //    后者现委托 queryVisibleListByUserId、只返回 Personal/Team，会把 DM/Group 空间漏掉造成误拒。
        //  - 未指定：沿用既有逻辑（智能体发布空间 → 回落用户个人空间）。
        Long spaceId;
        Long requestedSpaceId = sandboxTaskAddDto.getSpaceId();
        if (requestedSpaceId != null) {
            SpaceUserDto spaceUser = spaceApplicationService.querySpaceUser(requestedSpaceId, RequestContext.get().getUserId());
            if (spaceUser == null) {
                return ReqResult.error("没有指定空间的权限，无法在该空间创建定时任务");
            }
            spaceId = requestedSpaceId;
        } else {
            List<SpaceDto> spaceDtos = spaceApplicationService.queryListByUserId(RequestContext.get().getUserId());
            spaceId = spaceDtos.contains(publishedDto.getSpaceId()) ? publishedDto.getSpaceId() : null;
            if (spaceId == null) {
                SpaceDto spaceDto1 = spaceDtos.stream().filter(spaceDto -> spaceDto.getType() == Space.Type.Personal).findFirst().orElse(null);
                if (spaceDto1 == null) {
                    return ReqResult.error("用户缺少个人空间数据，无法创建当前任务");
                }
                spaceId = spaceDto1.getId();
            }
        }

        ScheduleTaskDto scheduleTaskDto = new ScheduleTaskDto();
        scheduleTaskDto.setCron(sandboxTaskAddDto.getCron());
        scheduleTaskDto.setTargetType("Agent");
        scheduleTaskDto.setTargetName(publishedDto.getName());
        scheduleTaskDto.setTargetId(String.valueOf(akAgentId));
        scheduleTaskDto.setTaskName(sandboxTaskAddDto.getTaskName());
        scheduleTaskDto.setTaskId(UUID.randomUUID().toString().replace("-", ""));
        scheduleTaskDto.setTenantId(RequestContext.get().getTenantId());
        scheduleTaskDto.setCreatorId(RequestContext.get().getUserId());
        scheduleTaskDto.setBeanId("taskCenterApplicationService");
        scheduleTaskDto.setSpaceId(spaceId);
        if (sandboxTaskAddDto.getExecuteType().equals("Once")) {
            Assert.notNull(sandboxTaskAddDto.getLockTime(), "执行时间不能为空");
            scheduleTaskDto.setMaxExecTimes(1L);
            scheduleTaskDto.setCron(toCron(sandboxTaskAddDto.getLockTime().getTime()));
        } else {
            Assert.notNull(sandboxTaskAddDto.getCron(), "定时cron表达式不能为空");
            scheduleTaskDto.setMaxExecTimes(Long.MAX_VALUE);
        }
        Map<String, Object> params = new HashMap<>();
        params.put("conversationId", conversationByCid.getId());
        params.put("keepConversation", 1);
        params.put("message", sandboxTaskAddDto.getMessage());
        scheduleTaskDto.setParams(params);
        Long id = scheduleTaskApiService.start(scheduleTaskDto);
        // ★ start() 内部会吞掉异常并回 0（ScheduleTaskApplicationServiceImpl），任务其实没建。
        //   不判这一下，cron 写错（如用 5 字段 crontab 写法）会回「成功 + id=0」——
        //   调用方以为建好了，实际什么都没有，是最难查的一种失败。
        if (id == null || id <= 0) {
            log.warn("沙盒创建定时任务未生效 targetId={} taskName={} executeType={} cron={}",
                    scheduleTaskDto.getTargetId(), scheduleTaskDto.getTaskName(),
                    sandboxTaskAddDto.getExecuteType(), scheduleTaskDto.getCron());
            return ReqResult.error("任务创建失败，请检查 cron 表达式（需 Quartz 6 字段）后重试");
        }
        SandboxTaskCreateResult sandboxTaskCreateResult = new SandboxTaskCreateResult();
        sandboxTaskCreateResult.setId(id);
        return ReqResult.success(sandboxTaskCreateResult);
    }


    @Operation(summary = "更新定时任务")
    @RequestMapping(path = "/update", method = RequestMethod.POST)
    public ReqResult<Void> update(@RequestBody SandboxTaskUpdateDto sandboxTaskUpdateDto) {
        ScheduleTaskDto scheduleTaskDto1 = scheduleTaskApiService.queryById(sandboxTaskUpdateDto.getId());
        if (scheduleTaskDto1 == null) {
            return ReqResult.error("任务不存在");
        }
        Long akAgentId = resolveAkAgentId((UserAccessKeyDto) RequestContext.get().getUserAccessKey());
        if (akAgentId == null || !String.valueOf(akAgentId).equals(scheduleTaskDto1.getTargetId())) {
            return ReqResult.error("Invalid task id");
        }
        ScheduleTaskDto scheduleTaskDto = new ScheduleTaskDto();
        scheduleTaskDto.setId(sandboxTaskUpdateDto.getId());
        scheduleTaskDto.setTaskName(sandboxTaskUpdateDto.getTaskName());
        if (sandboxTaskUpdateDto.getExecuteType() != null) {
            if (sandboxTaskUpdateDto.getExecuteType().equals("Once")) {
                Assert.notNull(sandboxTaskUpdateDto.getLockTime(), "执行时间不能为空");
                scheduleTaskDto.setCron(toCron(sandboxTaskUpdateDto.getLockTime().getTime()));
                scheduleTaskDto.setMaxExecTimes(1L);
            } else {
                Assert.notNull(sandboxTaskUpdateDto.getCron(), "定时cron表达式不能为空");
                scheduleTaskDto.setMaxExecTimes(Long.MAX_VALUE);
            }
        }
        if (StringUtils.isNotBlank(sandboxTaskUpdateDto.getMessage())) {
            Map<String, Object> params = (Map<String, Object>) scheduleTaskDto1.getParams();
            if (params != null) {
                params.put("message", sandboxTaskUpdateDto.getMessage());
            }
            scheduleTaskDto.setParams(params);
        }
        scheduleTaskApiService.updateById(scheduleTaskDto);
        return ReqResult.success();
    }


    @Operation(summary = "取消定时任务")
    @RequestMapping(path = "/cancel/{id}", method = RequestMethod.POST)
    public ReqResult<Void> cancel(@PathVariable Long id) {
        ScheduleTaskDto scheduleTaskDto1 = scheduleTaskApiService.queryById(id);
        if (scheduleTaskDto1 == null) {
            return ReqResult.error("任务不存在");
        }
        Long akAgentId = resolveAkAgentId((UserAccessKeyDto) RequestContext.get().getUserAccessKey());
        if (akAgentId == null || !String.valueOf(akAgentId).equals(scheduleTaskDto1.getTargetId())) {
            return ReqResult.error("Invalid task id");
        }
        ScheduleTaskDto scheduleTaskDto = new ScheduleTaskDto();
        scheduleTaskDto.setId(id);
        scheduleTaskDto.setStatus(ScheduleTaskDto.Status.CANCEL);
        scheduleTaskApiService.updateById(scheduleTaskDto);
        return ReqResult.success();
    }

    @Operation(summary = "查询当前Agent的定时任务列表")
    @RequestMapping(path = "/list", method = RequestMethod.POST)
    public ReqResult<List<SandboxTaskDto>> list() {
        Long akAgentId = resolveAkAgentId((UserAccessKeyDto) RequestContext.get().getUserAccessKey());
        if (akAgentId == null) {
            return ReqResult.error(INVALID_AK_MESSAGE);
        }
        List<ScheduleTaskDto> scheduleTaskDtoList = scheduleTaskApiService.queryTaskListByUserIdAndAgentId(RequestContext.get().getUserId(), String.valueOf(akAgentId));
        return ReqResult.success(scheduleTaskDtoList.stream().map(scheduleTaskDto -> {
            SandboxTaskDto sandboxTaskDto = new SandboxTaskDto();
            sandboxTaskDto.setId(scheduleTaskDto.getId());
            sandboxTaskDto.setTaskName(scheduleTaskDto.getTaskName());
            sandboxTaskDto.setExecuteType(scheduleTaskDto.getMaxExecTimes() == 1 ? "Once" : "Loop");
            sandboxTaskDto.setCron(scheduleTaskDto.getCron());
            sandboxTaskDto.setLockTime(scheduleTaskDto.getLockTime());
            Map<String, Object> params = (Map<String, Object>) scheduleTaskDto.getParams();
            if (params != null) {
                sandboxTaskDto.setMessage(String.valueOf(params.get("message")));
                try {
                    sandboxTaskDto.setConversationId(Long.parseLong(String.valueOf(params.get("conversationId"))));
                } catch (NumberFormatException ignore) {
                }
            }
            return sandboxTaskDto;
        }).collect(Collectors.toList()));
    }


    // ------------------------------------------------------------------ 身份与范围

    private static final String INVALID_AK_MESSAGE = "仅支持绑定智能体的访问密钥（AK）调用本接口";

    /**
     * 从 AK 解出「这把钥匙锚定哪个智能体」，解不出返回 null。与
     * {@code AgentTaskForSandboxController#resolveAkAgentId} 同源（同一把沙盒钥匙，两处必须一致）。
     *
     * <p>★两种形态都得认 —— 沙盒里默认拿到的就是后者：
     * <ul>
     *   <li>{@code Agent} 型（智能体页签里建的钥匙）：{@code targetId} 就是 agentId；</li>
     *   <li>{@code Sandbox} 型：{@code targetId} 是 {@code "{agentId}-{conversationId}"}。这是
     *       {@code ChatApplicationServiceImpl} 铸沙盒钥匙时拼的，并作为 {@code SANDBOX_ACCESS_KEY}
     *       注入沙盒环境变量 —— 所以技能手里那把必然是 Sandbox 型。取第一个 {@code '-'} 之前的部分。</li>
     * </ul>
     * 其余类型（Tenant / OpenApi / Mcp / TempChat …）不代表某个具体智能体，没有可锚定的可见范围，一律不认。
     */
    private static Long resolveAkAgentId(UserAccessKeyDto ak) {
        if (ak == null) {
            return null;
        }
        String targetId = ak.getTargetId();
        if (StringUtils.isBlank(targetId)) {
            return null;
        }
        if (ak.getTargetType() == UserAccessKeyDto.AKTargetType.Agent) {
            return parsePositiveLong(targetId);
        }
        if (ak.getTargetType() == UserAccessKeyDto.AKTargetType.Sandbox) {
            int dash = targetId.indexOf('-');
            return parsePositiveLong(dash < 0 ? targetId : targetId.substring(0, dash));
        }
        return null;
    }

    /** 解析成正数 ID；非数字 / 非正数一律 null（宁可不认，也不锚到 0 或负数上）。 */
    private static Long parsePositiveLong(String raw) {
        try {
            long value = Long.parseLong(raw.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String toCron(long timestamp) {
        try {
            // 转换为本地时间
            LocalDateTime dateTime = LocalDateTime.ofEpochSecond(timestamp / 1000, 0, ZoneId.systemDefault().getRules().getOffset(Instant.now()));
            // 生成 Cron 表达式（Spring 格式：秒 分 时 日 月 周 年）
            return String.format("%d %d %d %d %d ?",
                    dateTime.getSecond(),
                    dateTime.getMinute(),
                    dateTime.getHour(),
                    dateTime.getDayOfMonth(),
                    dateTime.getMonthValue());
        } catch (Exception e) {
            return null;
        }
    }
}
