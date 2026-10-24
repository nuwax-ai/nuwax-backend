package com.xspaceagi.agent.core.adapter.dto;

import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class GuidQuestionDto {

    @Schema(description = "问题类型")
    private GuidQuestionType type;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "问题信息")
    private String info;

    @Schema(description = "图标")
    private String icon;

    @Schema(description = "链接地址，type类型为Link时有效")
    private String url;

    @Schema(description = "页面ID，type类型为Page时有效")
    private Long pageId;

    @Schema(description = "页面基础路径，type类型为Page时有效")
    private String basePath;

    @Schema(description = "页面路径，type类型为Page时有效")
    private String pageUri;

    @Schema(description = "页面地址，配置更新时不需要传递，type类型为Page时有效，完整的页面地址，前端需要使用 BASE_URL+pageUrl")
    private String pageUrl;

    @Schema(description = "参数配置")
    private List<Arg> args;

    @Schema(description = "参数值，配置更新时不需要传递，用户点击跳转时直接使用，type类型为Page时有效")
    private Map<String, Object> params;

    public enum GuidQuestionType {
        Question,
        Page,
        Link
    }
}
