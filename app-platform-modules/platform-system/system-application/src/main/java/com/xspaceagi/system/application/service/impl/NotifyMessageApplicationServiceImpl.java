package com.xspaceagi.system.application.service.impl;

import com.xspaceagi.system.application.dto.*;
import com.xspaceagi.system.domain.service.NotifyMessageDomainService;
import com.xspaceagi.system.application.service.AuthService;
import com.xspaceagi.system.application.service.NotifyMessageApplicationService;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.infra.dao.entity.NotifyMessage;
import com.xspaceagi.system.infra.dao.entity.NotifyMessageUser;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.jackson.JsonSerializeUtil;
import com.xspaceagi.system.spec.utils.RedisUtil;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class NotifyMessageApplicationServiceImpl implements NotifyMessageApplicationService {

    @Resource
    private NotifyMessageDomainService notifyMessageDomainService;

    @Resource
    private UserApplicationService userApplicationService;

    @Resource
    private TenantConfigApplicationService tenantConfigApplicationService;

    @Resource
    private AuthService authService;

    @Resource
    private RedisUtil redisUtil;

    @Override
    public Long sendNotifyMessage(SendNotifyMessageDto sendNotifyMessageDto) {
        NotifyMessage notifyMessage = NotifyMessage.builder()
                .tenantId(sendNotifyMessageDto.getTenantId())
                .scope(sendNotifyMessageDto.getScope())
                .senderId(sendNotifyMessageDto.getSenderId())
                .content(sendNotifyMessageDto.getContent())
                .build();
        if (sendNotifyMessageDto.getScope() == NotifyMessage.MessageScope.Broadcast) {
            Long lastUserId = Long.MAX_VALUE;
            Integer size = 1000;
            while (size > 0) {
                List<Long> ids = userApplicationService.queryUserIdList(lastUserId, size);
                if (ids.size() == 0) {
                    break;
                }
                notifyMessageDomainService.addNotifyMessage(notifyMessage, ids);
                ids.forEach(userId -> publishEvent(userId, EventDto.builder().type(EventDto.EVENT_TYPE_NEW_NOTIFY_MESSAGE).event(notifyMessage).build()));
                lastUserId = ids.get(ids.size() - 1);
                size = ids.size();
            }
        } else {
            notifyMessageDomainService.addNotifyMessage(notifyMessage, sendNotifyMessageDto.getUserIds());
            sendNotifyMessageDto.getUserIds().forEach(userId -> publishEvent(userId, EventDto.builder().type(EventDto.EVENT_TYPE_NEW_NOTIFY_MESSAGE).event(notifyMessage).build()));
        }
        return notifyMessage.getId();
    }

    @Override
    public List<NotifyMessageDto> queryNotifyMessageList(NotifyMessageQueryDto messageQueryDto) {
        List<NotifyMessageUser> notifyMessageUserList = notifyMessageDomainService.queryNotifyMessageUserList(messageQueryDto.getUserId(), messageQueryDto.getLastId(), messageQueryDto.getSize(), messageQueryDto.getReadStatus());
        //从notifyMessageUserList中获取notifyId列表，然后查询消息
        List<Long> notifyIds = notifyMessageUserList.stream().map(NotifyMessageUser::getNotifyId).toList();
        List<NotifyMessage> notifyMessageList = notifyMessageDomainService.queryNotifyMessageList(notifyIds);
        //获取senderId列表，然后查询用户信息，转成userId为key的map
        List<Long> senderIds = notifyMessageList.stream().map(NotifyMessage::getSenderId).toList();
        Map<Long, UserDto> userMap = userApplicationService.queryUserListByIds(senderIds).stream().collect(Collectors.toMap(UserDto::getId, userDto -> userDto));

        //notifyMessageList转以notfyId为key的map
        Map<Long, NotifyMessage> notifyMessageMap = notifyMessageList.stream().collect(Collectors.toMap(NotifyMessage::getId, notifyMessage -> notifyMessage, (o1, o2) -> o1));
        //notifyMessageUserList转List<NotifyMessageDto>
        return notifyMessageUserList.stream().map(notifyMessageUser -> {
                    NotifyMessage notifyMessage = notifyMessageMap.get(notifyMessageUser.getNotifyId());
                    NotifyMessageDto notifyMessageDto = new NotifyMessageDto();
                    if (notifyMessage == null) {
                        return notifyMessageDto;
                    }
                    notifyMessageDto.setId(notifyMessage.getId());
                    notifyMessageDto.setContent(notifyMessage.getContent());
                    notifyMessageDto.setReadStatus(notifyMessageUser.getReadStatus());
                    notifyMessageDto.setCreated(notifyMessage.getCreated());
                    UserDto userDto = userMap.get(notifyMessage.getSenderId());
                    if (userDto != null) {
                        notifyMessageDto.setSender(NotifyMessageDto.Sender.builder()
                                .userId(userDto.getId())
                                .userName(userDto.getUserName())
                                .nickName(userDto.getNickName())
                                .avatar(userDto.getAvatar())
                                .build());
                    } else {
                        notifyMessageDto.setSender(NotifyMessageDto.Sender.builder()
                                .userId(-1L)
                                .userName("--")
                                .nickName("--")
                                .avatar("")
                                .build());
                    }
                    if (notifyMessage.getScope() != NotifyMessage.MessageScope.Private) {
                        TenantConfigDto tenantConfigDto;
                        if (RequestContext.get() == null || !RequestContext.get().getTenantId().equals(notifyMessage.getTenantId())) {
                            tenantConfigDto = tenantConfigApplicationService.getTenantConfig(notifyMessage.getTenantId());
                        } else {
                            tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
                        }
                        notifyMessageDto.setSender(NotifyMessageDto.Sender.builder()
                                .userId(-1L)
                                .avatar(tenantConfigDto.getSiteLogo())
                                .nickName(tenantConfigDto.getOfficialUserName())
                                .userName(tenantConfigDto.getOfficialUserName())
                                .build());
                    }
                    return notifyMessageDto;
                }).toList().stream().filter(notifyMessageDto -> notifyMessageDto.getId() != null)
                .collect(Collectors.toList());
    }

    @Override
    public Long countUnreadNotifyMessage(Long userId) {
        return notifyMessageDomainService.countUnreadNotifyMessage(userId);
    }

    @Override
    public void updateReadStatus(Long userId, List<Long> notifyIds) {
        notifyMessageDomainService.updateReadStatus(userId, notifyIds);
    }

    @Override
    public void updateAllUnreadNotifyMessage(Long userId) {
        notifyMessageDomainService.updateAllUnreadNotifyMessage(userId);
    }

    @Override
    public void publishEvent(Long userId, EventDto<?> eventDto) {
        Assert.notNull(userId, "userId must be non-null");
        Assert.notNull(eventDto, "eventDto must be non-null");
        authService.getUserClientIds(userId).forEach(clientId -> {
            if (StringUtils.isNotBlank(clientId)) {
                redisUtil.leftPush("event_queue:" + clientId, JsonSerializeUtil.toJSONStringGeneric(eventDto));
                redisUtil.expire("event_queue:" + clientId, 60 * 60 * 24);
            }
        });
    }

    @Override
    public List<EventDto<?>> collectEventList(Long userId, String clientId) {
        if (StringUtils.isNotBlank(clientId)) {
            List<Object> eventList = redisUtil.range("event_queue:" + clientId, 0, -1);
            if (eventList != null && !eventList.isEmpty()) {
                return eventList.stream().map(event -> (EventDto<?>) JsonSerializeUtil.parseObjectGeneric(event.toString())).collect(Collectors.toList());
            }
        }
        return List.of();
    }

    @Override
    public void clearEventList(Long userId, String clientId) {
        if (StringUtils.isNotBlank(clientId)) {
            redisUtil.expire("event_queue:" + clientId, 0);
        }
    }
}
