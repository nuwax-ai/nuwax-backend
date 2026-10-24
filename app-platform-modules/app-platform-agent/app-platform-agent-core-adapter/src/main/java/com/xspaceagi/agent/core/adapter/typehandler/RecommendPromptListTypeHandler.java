package com.xspaceagi.agent.core.adapter.typehandler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xspaceagi.agent.core.adapter.dto.recommend.RecommendPrompt;
import com.xspaceagi.system.spec.common.ListJsonTypeHandler;

import java.util.List;

public class RecommendPromptListTypeHandler extends ListJsonTypeHandler<RecommendPrompt> {

    @Override
    protected TypeReference<List<RecommendPrompt>> getTypeReference() {
        return new TypeReference<List<RecommendPrompt>>() {
        };
    }
}
