package com.xspaceagi.agent.core.adapter.typehandler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xspaceagi.system.spec.common.ListJsonTypeHandler;

import java.util.List;

public class StringListTypeHandler extends ListJsonTypeHandler<String> {

    @Override
    protected TypeReference<List<String>> getTypeReference() {
        return new TypeReference<List<String>>() {
        };
    }
}
