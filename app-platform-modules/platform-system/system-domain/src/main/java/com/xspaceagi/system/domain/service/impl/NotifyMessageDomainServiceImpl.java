package com.xspaceagi.system.domain.service.impl;

import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xspaceagi.system.domain.service.NotifyMessageDomainService;
import com.xspaceagi.system.infra.dao.entity.NotifyMessage;
import com.xspaceagi.system.infra.dao.entity.NotifyMessageUser;
import com.xspaceagi.system.infra.dao.service.NotifyMessageService;
import com.xspaceagi.system.infra.dao.service.NotifyMessageUserService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class NotifyMessageDomainServiceImpl implements NotifyMessageDomainService {

    @Resource
    private NotifyMessageService notifyMessageService;

    @Resource
    private NotifyMessageUserService notifyMessageUserService;

    @Override
    @DSTransactional
    public void addNotifyMessage(NotifyMessage notifyMessage, List<Long> userIds) {
        notifyMessageUserService.saveBatch(userIds.stream().map(userId -> {
            NotifyMessageUser notifyMessageUser = new NotifyMessageUser();
            notifyMessageUser.setUserId(userId);
            notifyMessageUser.setNotifyId(notifyMessage.getId());
            notifyMessageUser.setReadStatus(NotifyMessageUser.ReadStatus.Unread);
            return notifyMessageUser;
        }).collect(Collectors.toList()));
    }

    @Override
    public void updateReadStatus(Long userId, List<Long> notifyIds) {
        LambdaQueryWrapper<NotifyMessageUser> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(NotifyMessageUser::getNotifyId, notifyIds);
        queryWrapper.eq(NotifyMessageUser::getUserId, userId);
        NotifyMessageUser notifyMessageUser = new NotifyMessageUser();
        notifyMessageUser.setReadStatus(NotifyMessageUser.ReadStatus.Read);
        notifyMessageUserService.update(notifyMessageUser, queryWrapper);
    }

    @Override
    public List<NotifyMessageUser> queryNotifyMessageUserList(Long userId, Long lastId, Integer size, NotifyMessageUser.ReadStatus readStatus) {
        if (lastId == null) {
            lastId = Long.MAX_VALUE;
        }
        if (size == null || size <= 0) {
            size = 10;
        }
        LambdaQueryWrapper<NotifyMessageUser> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(NotifyMessageUser::getUserId, userId);
        queryWrapper.lt(NotifyMessageUser::getNotifyId, lastId);
        if (readStatus != null) {
            queryWrapper.eq(NotifyMessageUser::getReadStatus, readStatus);
        }
        queryWrapper.last("limit " + size);
        queryWrapper.orderByDesc(NotifyMessageUser::getNotifyId);
        return notifyMessageUserService.list(queryWrapper);
    }

    @Override
    public List<NotifyMessage> queryNotifyMessageList(List<Long> notifyIds) {
        if (notifyIds.isEmpty()) {
            return new ArrayList<>();
        }
        LambdaQueryWrapper<NotifyMessage> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(NotifyMessage::getId, notifyIds);
        return notifyMessageService.list(queryWrapper);
    }

    @Override
    public Long countUnreadNotifyMessage(Long userId) {
        LambdaQueryWrapper<NotifyMessageUser> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(NotifyMessageUser::getUserId, userId);
        queryWrapper.eq(NotifyMessageUser::getReadStatus, NotifyMessageUser.ReadStatus.Unread);
        return notifyMessageUserService.count(queryWrapper);
    }

    @Override
    public void updateAllUnreadNotifyMessage(Long userId) {
        LambdaQueryWrapper<NotifyMessageUser> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(NotifyMessageUser::getUserId, userId);
        queryWrapper.eq(NotifyMessageUser::getReadStatus, NotifyMessageUser.ReadStatus.Unread);

        NotifyMessageUser notifyMessageUser = new NotifyMessageUser();
        notifyMessageUser.setReadStatus(NotifyMessageUser.ReadStatus.Read);
        notifyMessageUserService.update(notifyMessageUser, queryWrapper);
    }
}
