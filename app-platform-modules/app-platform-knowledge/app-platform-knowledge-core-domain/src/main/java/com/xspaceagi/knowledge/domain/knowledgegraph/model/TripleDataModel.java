package com.xspaceagi.knowledge.domain.knowledgegraph.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.xspaceagi.knowledge.domain.knowledgegraph.service.IKnowledgeGraphDomainExtService;

import java.util.List;

@lombok.Data
@lombok.AllArgsConstructor
@lombok.NoArgsConstructor
public class TripleDataModel {

    /*
    @JsonPropertyDescription("三元组中的主体（主语），即动作的发起者或关系的起点。例如在'小猫带来了鱼'中为'小猫'")
    private String subject;

    @JsonPropertyDescription("三元组中的谓语（关系/动作），描述主体与客体之间的动作或联系。例如在'小猫带来了鱼'中为'带来了'")
    private String predicate;

    @JsonPropertyDescription("三元组中的客体（宾语），即动作的承受者或关系的终点。例如在'小猫带来了鱼'中为'鱼'")
    private String object;

    @JsonPropertyDescription("与当前谓词语义相近或同义的其他谓语列表，例如'带来了'的相近谓语可能是'拿来了','送来了'。格式：similarPredicates=[\"包含\",\"具有\",\"拥有\",\"包括\",\"含有\",\"具备\",\"配备\",\"配置\",\"搭载\",\"集成\",\"内置\",\"附带\",\"涵盖\",\"囊括\",\"容纳\",\"承载\",\"设置\",\"安排\",\"部署\",\"安装\"]")
    private String similarPredicates;*/

    @JsonPropertyDescription("三元组中的对象数据")
    private List<IKnowledgeGraphDomainExtService.TripleData> tripleData;

}
