package com.xspaceagi.agent.core.adapter.application;

import java.util.List;

import com.xspaceagi.agent.core.adapter.dto.ComponentDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;

public interface ComponentApplicationService {

    List<ComponentDto> getComponentListBySpaceId(Long spaceId, Long groupId, List<Published.TargetType> types);
}
