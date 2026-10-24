package com.xspaceagi.agent.core.adapter.application;

import com.xspaceagi.agent.core.adapter.dto.AgentOutputDto;
import com.xspaceagi.agent.core.adapter.dto.TryReqDto;
import reactor.core.publisher.Flux;

import java.util.Map;

public interface ChatApplicationService {

    Flux<AgentOutputDto> chat(TryReqDto tryReqDto, Map<String, String> headersFromRequest, boolean isTempChat);

    Flux<AgentOutputDto> chat(TryReqDto tryReqDto, Map<String, String> headersFromRequest, boolean isTempChat, Boolean devMode);

    Flux<AgentOutputDto> subscribe(Long conversationId);

    void setChatStopStatus(String conversationId);

    boolean isChatStop(String conversationId);

    void stopChat(String conversationId);

    Long getSelectedModelId(Long userId, Long agentId);
}
