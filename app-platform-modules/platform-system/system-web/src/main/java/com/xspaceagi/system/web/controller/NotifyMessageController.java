package com.xspaceagi.system.web.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.xspaceagi.eco.market.sdk.reponse.ClientSecretResponse;
import com.xspaceagi.system.application.dto.*;
import com.xspaceagi.system.application.service.AuthService;
import com.xspaceagi.system.application.service.NotifyMessageApplicationService;
import com.xspaceagi.system.infra.dao.entity.NotifyMessage;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.infra.rpc.ClientSecretRpcService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.utils.HttpClient;
import com.xspaceagi.system.web.dto.EventRespDto;
import com.xspaceagi.system.web.dto.PullMessageAckDto;
import com.xspaceagi.system.web.dto.PullMessageRequestDto;
import com.xspaceagi.system.web.dto.PullMessageResponseDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Tag(name = "消息通知相关接口")
@RestController
@RequestMapping("/api/notify")
public class NotifyMessageController {

    @Resource
    private NotifyMessageApplicationService notifyMessageApplicationService;

    @Resource
    private AuthService authService;

    @Resource
    private HttpClient httpClient;

    @Value("${installation-source}")
    private String installationSource;

    @Value("${app.version}")
    private String version;

    @Value("${eco-market.server.base-url:}")
    private String cloudApi;

    @Value("${eco-market.server.cancelHB:}")
    private String cancelHB;

    @Resource
    private ClientSecretRpcService clientSecretRpcService;


    @Operation(summary = "查询用户消息列表")
    @RequestMapping(path = "/message/list", method = RequestMethod.POST)
    public ReqResult<List<NotifyMessageDto>> listQuery(@RequestBody NotifyMessageQueryDto messageQueryDto) {
        messageQueryDto.setUserId(RequestContext.get().getUserId());
        return ReqResult.success(notifyMessageApplicationService.queryNotifyMessageList(messageQueryDto));
    }

    private void pullMessage() {
        UserDto userDto = (UserDto) RequestContext.get().getUser();
        TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
        if (userDto.getRole() == User.Role.Admin && StringUtils.isBlank(cancelHB)) {
            // 管理员拉取云端消息
            ClientSecretResponse clientSecretResponse = clientSecretRpcService.queryClientSecret(tenantConfigDto.getTenantId());
            if (clientSecretResponse != null) {
                PullMessageRequestDto pullMessageRequestDto = new PullMessageRequestDto();
                pullMessageRequestDto.setTenantName(tenantConfigDto.getSiteName());
                pullMessageRequestDto.setSiteUrl(tenantConfigDto.getSiteUrl());
                pullMessageRequestDto.setClientId(clientSecretResponse.getClientId());
                pullMessageRequestDto.setClientSecret(clientSecretResponse.getClientSecret());
                pullMessageRequestDto.setInstallationSource(installationSource);
                pullMessageRequestDto.setVersion(version);
                pullMessageRequestDto.setUser(userDto.getNickName() != null ? userDto.getNickName() : userDto.getUserName());
                pullMessageRequestDto.setUid(userDto.getUid());
                String content = httpClient.post(cloudApi + "/api/message/pull", JSON.toJSONString(pullMessageRequestDto), Map.of("Authorization", "Bearer " + clientSecretResponse.getClientSecret()));
                ReqResult<List<PullMessageResponseDto>> pullMessageResponseDtos = JSON.parseObject(content, new TypeReference<ReqResult<List<PullMessageResponseDto>>>() {
                });
                if (pullMessageResponseDtos != null && CollectionUtils.isNotEmpty(pullMessageResponseDtos.getData())) {
                    pullMessageResponseDtos.getData().forEach(pullMessageResponseDto -> notifyMessageApplicationService.sendNotifyMessage(SendNotifyMessageDto.builder()
                            .scope(NotifyMessage.MessageScope.System)
                            .content(pullMessageResponseDto.getContent())
                            .senderId(RequestContext.get().getUserId())
                            .userIds(Arrays.asList(RequestContext.get().getUserId()))
                            .build()));
                    PullMessageAckDto pullMessageAckDto = new PullMessageAckDto();
                    pullMessageAckDto.setClientId(clientSecretResponse.getClientId());
                    pullMessageAckDto.setClientSecret(clientSecretResponse.getClientSecret());
                    pullMessageAckDto.setUid(userDto.getUid());
                    pullMessageAckDto.setMessageIds(pullMessageResponseDtos.getData().stream().map(PullMessageResponseDto::getMessageId).collect(Collectors.toList()));
                    httpClient.post(cloudApi + "/api/message/ack", JSON.toJSONString(pullMessageAckDto), Map.of("Authorization", "Bearer " + clientSecretResponse.getClientSecret()));
                }
            }
        }
    }

    @Operation(summary = "查询用户未读消息数量")
    @RequestMapping(path = "/message/unread/count", method = RequestMethod.GET)
    public ReqResult<Long> unReadCount() {
        return ReqResult.success(notifyMessageApplicationService.countUnreadNotifyMessage(RequestContext.get().getUserId()));
    }

    @Operation(summary = "清除所有未读消息")
    @RequestMapping(path = "/message/unread/clear", method = RequestMethod.GET)
    public ReqResult<Void> unReadClear() {
        notifyMessageApplicationService.updateAllUnreadNotifyMessage(RequestContext.get().getUserId());
        return ReqResult.success();
    }

    @Operation(summary = "更新指定未读消息为已读", description = "消息ID列表作为POST参数，例如 [1,2,4]")
    @RequestMapping(path = "/message/read", method = RequestMethod.POST)
    public ReqResult<Void> read(@RequestBody @Schema(description = "消息ID列表") List<Long> ids) {
        notifyMessageApplicationService.updateReadStatus(RequestContext.get().getUserId(), ids);
        return ReqResult.success();
    }

    @Operation(summary = "事件通知查询接口")
    @RequestMapping(path = "/event/collect/batch", method = RequestMethod.GET)
    public ReqResult<EventRespDto> collectEvent() {
        String clientId = authService.getClientId(RequestContext.get().getUserId(), RequestContext.get().getToken());
        List<EventDto<?>> eventDtos = notifyMessageApplicationService.collectEventList(RequestContext.get().getUserId(), clientId);
        EventRespDto eventRespDto = new EventRespDto();
        eventRespDto.setHasEvent(!eventDtos.isEmpty());
        eventRespDto.setEventList(eventDtos);
        eventRespDto.setVersion(version);
        try {
            // 之前的逻辑
            pullMessage();
        } catch (Exception e) {
            log.error("触发消息拉取事件失败", e);
        }
        return ReqResult.success(eventRespDto);
    }

    @Operation(summary = "事件通知清除接口")
    @RequestMapping(path = "/event/clear", method = RequestMethod.GET)
    public ReqResult<Void> clearEvent() {
        String clientId = authService.getClientId(RequestContext.get().getUserId(), RequestContext.get().getToken());
        notifyMessageApplicationService.clearEventList(RequestContext.get().getUserId(), clientId);
        return ReqResult.success();
    }

}