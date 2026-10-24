package com.xspaceagi.userapp.infra.dao.typehandler;

import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xspaceagi.system.spec.common.ListJsonTypeHandler;
import com.xspaceagi.userapp.sdk.dto.BuildVersionDto;

public class BuildVersionListTypeHandler extends ListJsonTypeHandler<BuildVersionDto> {

    @Override
    protected TypeReference<List<BuildVersionDto>> getTypeReference() {
        return new TypeReference<List<BuildVersionDto>>() {
        };
    }
}
