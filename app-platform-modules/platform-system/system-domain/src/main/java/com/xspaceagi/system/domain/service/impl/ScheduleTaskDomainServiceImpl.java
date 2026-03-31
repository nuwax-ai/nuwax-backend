package com.xspaceagi.system.domain.service.impl;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xspaceagi.system.domain.service.ScheduleTaskDomainService;
import com.xspaceagi.system.infra.dao.entity.ScheduleTask;
import com.xspaceagi.system.infra.dao.service.ScheduleTaskService;
import com.xspaceagi.system.sdk.retry.annotation.Retry;
import com.xspaceagi.system.sdk.service.TaskExecuteService;
import com.xspaceagi.system.sdk.service.dto.ScheduleTaskDto;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.TriggerBuilder;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class ScheduleTaskDomainServiceImpl implements ScheduleTaskDomainService {

    private static final int MAX_EXEC_CT = 1000;

    @Resource
    private ScheduleTaskService scheduleTaskService;

    private ApplicationContext applicationContext;

    @Autowired
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(10, new CustomThreadFactory("schedule-task-executor"));

    @PostConstruct
    public void init() {
        // 查询状态为正在执行的数据，如果存在，修改状态，避免重启服务导致部分任务无法执行
        LambdaQueryWrapper<ScheduleTask> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.eq(ScheduleTask::getStatus, ScheduleTaskDto.Status.EXECUTING);
        ScheduleTask scheduleTaskUpdate = new ScheduleTask();
        scheduleTaskUpdate.setStatus(ScheduleTaskDto.Status.CONTINUE);
        scheduleTaskService.update(scheduleTaskUpdate, lambdaQueryWrapper);
        // 轮询任务数据
        executor.scheduleAtFixedRate(() -> executor.execute(() -> checkAndExecuteTask(new AtomicInteger(MAX_EXEC_CT))), 5, 1, TimeUnit.SECONDS);
    }

    private void checkAndExecuteTask(AtomicInteger ct) {
        ScheduleTask scheduleTaskForExec = queryOneAndLock();
        if (null != scheduleTaskForExec && ct.decrementAndGet() > 0) {
            String info = JSONObject.toJSONString(scheduleTaskForExec);
            log.debug("查询任务查询数据，第{}次处理：{}", scheduleTaskForExec.getExecTimes(), info);
            callback(scheduleTaskForExec).subscribe(res -> {
                log.debug("任务数据处理结束：{}", info);
                ScheduleTask scheduleTask = queryOne(scheduleTaskForExec.getTaskId());
                if (scheduleTask.getStatus() == ScheduleTaskDto.Status.COMPLETE || scheduleTask.getStatus() == ScheduleTaskDto.Status.CANCEL) {
                    checkAndExecuteTask(ct);
                    return;
                }
                scheduleTask.setStatus(res ? ScheduleTaskDto.Status.COMPLETE : ScheduleTaskDto.Status.CONTINUE);
                if (!res && scheduleTask.getExecTimes() >= scheduleTask.getMaxExecTimes()) {
                    scheduleTask.setStatus(ScheduleTaskDto.Status.OVERFLOW_MAX_EXEC_TIMES);
                }
                scheduleTask.setError("");
                scheduleTaskService.updateById(scheduleTask);
                checkAndExecuteTask(ct);
            }, e -> {
                log.error("任务回调业务模块失败 {}", JSONObject.toJSONString(scheduleTaskForExec), e);
                ScheduleTask taskUpdate = new ScheduleTask();
                taskUpdate.setStatus(ScheduleTaskDto.Status.FAIL);
                taskUpdate.setError(e.getMessage());
                taskUpdate.setId(scheduleTaskForExec.getId());
                taskUpdate.setLockTime(getNextExecDate(scheduleTaskForExec.getCron()));
                scheduleTaskService.updateById(taskUpdate);
            });
        }
    }

    public static class CustomThreadFactory implements ThreadFactory {

        private final String name;

        private final AtomicInteger count = new AtomicInteger(0);

        private CustomThreadFactory(String name) {
            this.name = name;
        }

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, "CustomThread-" + name + "-" + count.incrementAndGet());
        }
    }

    private ScheduleTask queryOneAndLock() {
        String taskServerInfo = System.getenv("TASK_SERVER_INFO");
        LambdaQueryWrapper<ScheduleTask> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.lt(ScheduleTask::getLockTime, new Date());
        lambdaQueryWrapper.in(ScheduleTask::getStatus, List.of(ScheduleTaskDto.Status.CREATE, ScheduleTaskDto.Status.FAIL, ScheduleTaskDto.Status.CONTINUE));
        if (StringUtils.isNotBlank(taskServerInfo)) {
            lambdaQueryWrapper.eq(ScheduleTask::getServerInfo, taskServerInfo);
        } else {
            lambdaQueryWrapper.isNull(ScheduleTask::getServerInfo);
        }
        lambdaQueryWrapper.last("LIMIT 1");
        lambdaQueryWrapper.orderByAsc(ScheduleTask::getLockTime);
        ScheduleTask scheduleTask = scheduleTaskService.getOne(lambdaQueryWrapper);
        if (scheduleTask == null) {
            return null;
        }
        LambdaUpdateWrapper<ScheduleTask> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(ScheduleTask::getId, scheduleTask.getId());
        updateWrapper.eq(ScheduleTask::getExecTimes, scheduleTask.getExecTimes());
        updateWrapper.set(ScheduleTask::getStatus, ScheduleTaskDto.Status.EXECUTING);
        if (StringUtils.isNotBlank(scheduleTask.getCron())) {
            updateWrapper.set(ScheduleTask::getLockTime, getNextExecDate(scheduleTask.getCron()));
        }
        updateWrapper.set(ScheduleTask::getExecTimes, scheduleTask.getExecTimes() + 1);
        updateWrapper.set(ScheduleTask::getLatestExecTime, new Date());
        if (!scheduleTaskService.update(updateWrapper)) {
            return null;
        }
        return scheduleTask;
    }

    private Mono<Boolean> callback(ScheduleTask scheduleTask) {
        TaskExecuteService taskExecuteService;
        try {
            taskExecuteService = (TaskExecuteService) applicationContext.getBean(scheduleTask.getBeanId());
        } catch (Exception e) {
            if (e instanceof NoSuchBeanDefinitionException) {
                log.error("任务回调业务模块不存在 {}", scheduleTask.getBeanId());
                return Mono.just(true);// 任务回调业务模块不存在，后续不再执行
            }
            return Mono.just(false);
        }
        ScheduleTaskDto scheduleTaskDto = new ScheduleTaskDto();
        BeanUtils.copyProperties(scheduleTask, scheduleTaskDto);
        try {
            return taskExecuteService.asyncExecute(scheduleTaskDto);
        } catch (Exception e) {
            return Mono.just(false);
        }
    }

    private ScheduleTask queryOne(String taskId) {
        LambdaQueryWrapper<ScheduleTask> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.eq(ScheduleTask::getTaskId, taskId);
        return scheduleTaskService.getOne(lambdaQueryWrapper, false);
    }

    @Retry
    @Override
    public Long start(ScheduleTask scheduleTask) {
        ScheduleTask scheduleTask1 = queryOne(scheduleTask.getTaskId());
        if (null != scheduleTask1) {
            // 曾按 taskId 注销后，再次 register 时若仅 return，
            // 记录仍为 CANCEL，重新start需把同 taskId 任务拉回到 CREATE。
            if (scheduleTask1.getStatus() == ScheduleTaskDto.Status.CANCEL) {
                if (StringUtils.isNotBlank(scheduleTask.getCron())) {
                    scheduleTask1.setCron(scheduleTask.getCron());
                }
                if (StringUtils.isNotBlank(scheduleTask.getBeanId())) {
                    scheduleTask1.setBeanId(scheduleTask.getBeanId());
                }
                if (scheduleTask.getParams() != null) {
                    scheduleTask1.setParams(scheduleTask.getParams());
                }
                if (scheduleTask.getMaxExecTimes() != null) {
                    scheduleTask1.setMaxExecTimes(scheduleTask.getMaxExecTimes());
                }
                if (StringUtils.isNotBlank(scheduleTask.getTaskName())) {
                    scheduleTask1.setTaskName(scheduleTask.getTaskName());
                }
                if (StringUtils.isNotBlank(scheduleTask.getTargetType())) {
                    scheduleTask1.setTargetType(scheduleTask.getTargetType());
                }
                if (StringUtils.isNotBlank(scheduleTask.getTargetId())) {
                    scheduleTask1.setTargetId(scheduleTask.getTargetId());
                }
                scheduleTask1.setStatus(ScheduleTaskDto.Status.CREATE);
                scheduleTask1.setExecTimes(0L);
                scheduleTask1.setError("");
                scheduleTask1.setLockTime(getNextExecDate(scheduleTask1.getCron()));
                scheduleTaskService.updateById(scheduleTask1);
                return scheduleTask1.getId();
            }
            return scheduleTask1.getId();
        }
        scheduleTask.setStatus(ScheduleTaskDto.Status.CREATE);
        if (scheduleTask.getLockTime() == null) {
            scheduleTask.setLockTime(getNextExecDate(scheduleTask.getCron()));
        }
        scheduleTaskService.save(scheduleTask);
        return scheduleTask.getId();
    }

    @Override
    public void update(ScheduleTask scheduleTask) {
        //根据taskId更新
        LambdaUpdateWrapper<ScheduleTask> lambdaQueryWrapper = new LambdaUpdateWrapper<>();
        lambdaQueryWrapper.eq(ScheduleTask::getTaskId, scheduleTask.getTaskId());
        if (StringUtils.isNotBlank(scheduleTask.getCron())) {
            scheduleTask.setLockTime(getNextExecDate(scheduleTask.getCron()));
        }
        scheduleTaskService.update(scheduleTask, lambdaQueryWrapper);
    }

    @Override
    public void complete(String taskId) {
        LambdaQueryWrapper<ScheduleTask> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.eq(ScheduleTask::getTaskId, taskId);
        ScheduleTask scheduleTask = scheduleTaskService.getOne(lambdaQueryWrapper);
        if (null != scheduleTask) {
            scheduleTask.setStatus(ScheduleTaskDto.Status.COMPLETE);
            scheduleTaskService.updateById(scheduleTask);
        }
    }

    @Override
    public void cancel(String taskId) {
        LambdaQueryWrapper<ScheduleTask> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.eq(ScheduleTask::getTaskId, taskId);
        lambdaQueryWrapper.notIn(ScheduleTask::getStatus, ScheduleTaskDto.Status.CANCEL, ScheduleTaskDto.Status.COMPLETE, ScheduleTaskDto.Status.OVERFLOW_MAX_EXEC_TIMES, ScheduleTaskDto.Status.FAIL);
        ScheduleTask scheduleTask = scheduleTaskService.getOne(lambdaQueryWrapper, false);
        if (null != scheduleTask) {
            scheduleTask.setStatus(ScheduleTaskDto.Status.CANCEL);
            scheduleTaskService.updateById(scheduleTask);
        }
    }

    public static Date getNextExecDate(String cron) {
        CronTrigger trigger = TriggerBuilder.newTrigger().withIdentity("Caclulate Date").withSchedule(CronScheduleBuilder.cronSchedule(cron)).build();
        Date time = trigger.getStartTime();
        return trigger.getFireTimeAfter(time);
    }
}
