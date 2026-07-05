package com.xspaceagi.agent.core.adapter.typehandler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xspaceagi.agent.core.adapter.dto.AgentPublishVersionDto;
import com.xspaceagi.system.spec.common.ListJsonTypeHandler;

import java.util.List;

public class AgentPublishVersionListTypeHandler extends ListJsonTypeHandler<AgentPublishVersionDto> {

    @Override
    protected TypeReference<List<AgentPublishVersionDto>> getTypeReference() {
        return new TypeReference<List<AgentPublishVersionDto>>() {};
    }
}
