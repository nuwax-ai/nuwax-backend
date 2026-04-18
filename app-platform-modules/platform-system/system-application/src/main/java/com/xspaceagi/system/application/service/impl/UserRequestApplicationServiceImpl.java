package com.xspaceagi.system.application.service.impl;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.system.application.service.UserRequestApplicationService;
import com.xspaceagi.system.infra.dao.entity.UserReq;
import com.xspaceagi.system.infra.dao.service.UserRequestService;
import com.xspaceagi.system.sdk.service.ScheduleTaskApiService;
import com.xspaceagi.system.sdk.service.TaskExecuteService;
import com.xspaceagi.system.sdk.service.dto.ScheduleTaskDto;
import com.xspaceagi.system.spec.utils.RedisUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.apache.poi.sl.draw.geom.GuideIf.Op.val;

@Slf4j
@Service("userRequestApplicationService")
public class UserRequestApplicationServiceImpl implements UserRequestApplicationService, TaskExecuteService {

    @Resource
    private UserRequestService userRequestService;

    @Resource
    private ScheduleTaskApiService scheduleTaskApiService;

    @Resource
    private RedisUtil redisUtil;

    @PostConstruct
    private void init() {
        scheduleTaskApiService.start(ScheduleTaskDto.builder()
                .taskId("userRequestApplicationService")
                .beanId("userRequestApplicationService")
                .maxExecTimes(Long.MAX_VALUE)
                .cron(ScheduleTaskDto.Cron.EVERY_MINUTE.getCron())
                .params(Map.of())
                .build());
    }

    @Override
    public void addUserRequest(UserReq userRequest) {
        try {
            redisUtil.leftPush("user_request_queue", JSON.toJSONString(userRequest));
        } catch (Exception e) {
            // 忽略
            log.error("Exception recording user request: {}", userRequest, e);
        }
    }

    @Override
    public Mono<Boolean> asyncExecute(ScheduleTaskDto scheduleTask) {
        return Mono.create(sink -> {
            try {
                Object val = redisUtil.rightPop("user_request_queue");
                List<UserReq> userRequestList = new ArrayList<>();
                while (val != null) {
                    log.debug("记录用户请求：{}", val);
                    try {
                        if (JSON.isValid(val.toString())) {
                            UserReq userRequest = JSON.parseObject(val.toString(), UserReq.class);
                            userRequestList.add(userRequest);
                        }
                        if (userRequestList.size() >= 100) {
                            userRequestService.addUserRequest(userRequestList);
                            userRequestList.clear();
                        }
                    } catch (Exception e) {
                        log.error("Exception recording user request: {}", val, e);
                    }
                    val = redisUtil.rightPop("user_request_queue");
                }
                if (!userRequestList.isEmpty()) {
                    userRequestService.addUserRequest(userRequestList);
                }
            } catch (Exception e) {
                // 忽略
                log.error("Exception recording user request: {}", val, e);
                sink.success(false);
            }
            sink.success(false);
        });
    }
}
