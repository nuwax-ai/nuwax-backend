package com.xspaceagi.system.sdk.service;

public interface INotificationRpcService {

    void sendNotifyMessage(Long userId, String message);

    void publishEvent(Long userId, String eventType, Object data);
}
