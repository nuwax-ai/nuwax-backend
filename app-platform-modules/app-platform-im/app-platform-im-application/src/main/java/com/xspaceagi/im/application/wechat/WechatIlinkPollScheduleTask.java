package com.xspaceagi.im.application.wechat;

import com.xspaceagi.system.sdk.service.AbstractTaskExecuteService;
import com.xspaceagi.system.sdk.service.dto.ScheduleTaskDto;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 系统定时任务入口：每个微信 iLink 渠道一条task
 */
@Slf4j
@Component(WechatIlinkLongPollService.SCHEDULE_TASK_BEAN_ID)
public class WechatIlinkPollScheduleTask extends AbstractTaskExecuteService {

    @Resource
    private WechatIlinkLongPollService wechatIlinkLongPollService;

    @Override
    protected boolean execute(ScheduleTaskDto scheduleTaskDto) {
        Long configId = resolveConfigId(scheduleTaskDto);
        if (configId == null) {
            log.warn("wechat ilink poll schedule task missing configId, taskId={}", scheduleTaskDto.getTaskId());
            return false;
        }
        wechatIlinkLongPollService.executePollOnce(configId);
        return false;
    }

    private static Long resolveConfigId(ScheduleTaskDto dto) {
        Object params = dto.getParams();
        if (params instanceof Map<?, ?> m) {
            Object c = m.get("configId");
            if (c instanceof Number n) {
                return n.longValue();
            }
            if (c != null) {
                try {
                    return Long.parseLong(c.toString());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        String tid = dto.getTaskId();
        String p = WechatIlinkLongPollService.ILINK_POLL_TASK_ID_PREFIX;
        if (tid != null && tid.startsWith(p)) {
            try {
                return Long.parseLong(tid.substring(p.length()));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
