package com.xspaceagi.agent.web.ui.controller.manage.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class ManageIdsQueryRequest implements Serializable {

    private List<Long> pageIds;

    private List<Long> agentIds;

    private List<Long> knowledgeIds;

}
