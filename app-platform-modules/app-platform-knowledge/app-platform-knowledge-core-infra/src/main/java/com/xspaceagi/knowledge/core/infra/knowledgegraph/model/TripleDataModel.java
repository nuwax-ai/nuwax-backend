package com.xspaceagi.knowledge.core.infra.knowledgegraph.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.xspaceagi.knowledge.core.infra.knowledgegraph.service.IKnowledgeGraphDomainService;
import com.xspaceagi.knowledge.domain.knowledgegraph.service.IKnowledgeGraphDomainExtService;

import java.util.List;

@lombok.Data
@lombok.AllArgsConstructor
@lombok.NoArgsConstructor
public class TripleDataModel {

    @JsonPropertyDescription("三元组中的对象数据")
    private List<IKnowledgeGraphDomainService.TripleData> tripleData;

}
