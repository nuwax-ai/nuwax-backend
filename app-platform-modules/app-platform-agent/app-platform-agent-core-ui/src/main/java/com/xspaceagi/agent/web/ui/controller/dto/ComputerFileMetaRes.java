package com.xspaceagi.agent.web.ui.controller.dto;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "文件元数据批量查询响应")
public class ComputerFileMetaRes implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "元数据列表，与请求 filePaths 顺序一致；单条失败仅该条带 error（其余字段为 null），不影响整批")
    private List<ComputerFileMetaItem> metas = new ArrayList<>();

    @SuppressWarnings("unchecked")
    public static ComputerFileMetaRes from(Map<String, Object> body) {
        ComputerFileMetaRes res = new ComputerFileMetaRes();
        if (body == null) {
            return res;
        }
        Object metas = body.get("metas");
        if (metas instanceof List<?> list) {
            res.setMetas(list.stream()
                    .filter(item -> item instanceof Map)
                    .map(item -> ComputerFileMetaItem.from((Map<String, Object>) item))
                    .toList());
        }
        return res;
    }
}
