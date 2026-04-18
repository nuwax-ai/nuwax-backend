package com.xspaceagi.knowledge.sdk.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 知识库配置请求参数
 */
@Builder
@Getter
@Setter
public class KnowledgeConfigRequestVo {


    @Schema(description = "页码")
    private Integer page;

    @Schema(description = "每页数量")
    private Integer pageSize;

//    @Schema(description = "分类名称")
//    private String category;

    @Schema(description = "关键字搜索")
    private String kw;

    @Schema(description = "空间ID（可选）需要通过空间过滤时有用")
    private Long spaceId;

    @Schema(description = "空间ID列表（可选）,查询用户有权限的空间,限制访问空间,比如工作流查询全部知识库,要限制用户有权限的空间下的知识库")
    private List<Long> authSpaceIds;

    @Schema(description = "创建人ID列表")
    private List<Long> creatorIds;

    @Schema(description = "数据类型,默认文本,1:文本;2:表格")
    private Integer dataType;

    //新增
    @Schema(description = "知识库的管控授权")
    private List<Long> knowledgeIds;

}
