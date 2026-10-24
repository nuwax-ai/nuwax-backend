package com.xspaceagi.userapp.infra.dao.typehandler;

import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xspaceagi.system.spec.common.ListJsonTypeHandler;
import com.xspaceagi.userapp.sdk.dto.RedirectUriEntry;

/** 补充回调地址条目列表 ↔ JSON 对象数组（user_project.redirect_uris） */
public class RedirectUriEntryListTypeHandler extends ListJsonTypeHandler<RedirectUriEntry> {

    @Override
    protected TypeReference<List<RedirectUriEntry>> getTypeReference() {
        return new TypeReference<List<RedirectUriEntry>>() {
        };
    }
}
