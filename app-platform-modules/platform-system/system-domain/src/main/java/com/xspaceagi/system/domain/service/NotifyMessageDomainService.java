package com.xspaceagi.system.domain.service;


import com.xspaceagi.system.infra.dao.entity.NotifyMessage;
import com.xspaceagi.system.infra.dao.entity.NotifyMessageUser;

import java.util.List;

public interface NotifyMessageDomainService {

    void addNotifyMessage(NotifyMessage notifyMessage, List<Long> userIds, boolean read);

    void addNotifyMessage(NotifyMessage notifyMessage, List<Long> userIds);

    void updateReadStatus(Long userId, List<Long> notifyIds);

    List<NotifyMessageUser> queryNotifyMessageUserList(Long userId, Long lastId, Integer size, NotifyMessageUser.ReadStatus readStatus);

    //根据notifyIds查询消息列表
    List<NotifyMessage> queryNotifyMessageList(List<Long> notifyIds);

    //统计用户未读消息数
    Long countUnreadNotifyMessage(Long userId);

    //更新用户所有未读状态为已读
    void updateAllUnreadNotifyMessage(Long userId);
}
