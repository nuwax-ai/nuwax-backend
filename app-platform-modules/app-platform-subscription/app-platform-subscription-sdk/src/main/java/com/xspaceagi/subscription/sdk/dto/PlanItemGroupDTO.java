package com.xspaceagi.subscription.sdk.dto;

import com.xspaceagi.system.sdk.service.dto.MergedGroupDataPermissionDto;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
public class PlanItemGroupDTO {
    @Schema(description = "分组描述")
    private String name;
    @Schema(description = "分组描述")
    private String description;
    @Schema(description = "分组类型")
    private GroupType groupType;
    @Schema(description = "分组项")
    private List<ItemDTO> items;
    @Schema(description = "有权限访问的开放 API, groupType为API时有效")
    private List<MergedGroupDataPermissionDto.OpenApiConfig> openApiConfigs;

    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    @Data
    public static class ItemDTO {
        @Schema(description = "名称")
        private String name;
        @Schema(description = "描述")
        private String description;
        @Schema(description = "图标")
        private String icon;
        @Schema(description = "是否选中")
        private boolean selected;
    }

    public enum GroupType {
        BASE,
        MODEL,
        AGENT,
        APP,
        KB,
        API,
    }
}
