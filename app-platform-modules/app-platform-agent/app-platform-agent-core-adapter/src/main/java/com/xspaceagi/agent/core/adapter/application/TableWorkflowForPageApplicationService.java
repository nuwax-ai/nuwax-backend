package com.xspaceagi.agent.core.adapter.application;

import com.xspaceagi.agent.core.adapter.dto.PageAppNewSqlDTO;
import com.xspaceagi.agent.core.adapter.dto.PageAppUpdateSqlDTO;
import com.xspaceagi.agent.core.adapter.dto.PageSqlResultDTO;

public interface TableWorkflowForPageApplicationService {

    PageSqlResultDTO tableNewSql(PageAppNewSqlDTO pageAppNewSqlDTO);

    PageSqlResultDTO tableUpdateSql(PageAppUpdateSqlDTO pageAppUpdateSqlDTO);
}
