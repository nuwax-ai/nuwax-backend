package com.xspaceagi.agent.core.adapter.application;

import com.xspaceagi.agent.core.adapter.dto.ProjectCreateDTO;
import com.xspaceagi.agent.core.adapter.dto.ProjectCreateResultDTO;

public interface ProjectApplicationService {

    ProjectCreateResultDTO createProject(ProjectCreateDTO projectCreateDTO);
}
