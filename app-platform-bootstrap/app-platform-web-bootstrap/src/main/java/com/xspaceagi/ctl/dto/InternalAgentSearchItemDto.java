package com.xspaceagi.ctl.dto;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

import com.xspaceagi.agent.core.spec.enums.UsageScenarioEnum;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 内部调用接口「已发布智能体搜索」的单条结果。
 * <p>
 * 有意不复用 {@code PublishedDto}：那个 DTO 面向平台前端，带 isCollect/isEnabled/subscribed/
 * price 等「以当前登录用户为视角」的字段（内部调用没有登录用户，这些字段恒为默认值，会误导调用方），
 * 还含 config 正文。这里只暴露调用方真正需要的发布态基础信息。
 */
@Data
public class InternalAgentSearchItemDto implements Serializable {

    @Schema(description = "发布记录ID（published.id），同一智能体发布到多个空间时会有多条")
    private Long publishedId;

    @Schema(description = "智能体ID（published.target_id），调用智能体能力时用这个")
    private Long agentId;

    @Schema(description = "发布名称")
    private String name;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "图标地址（空图时会补平台默认图标）")
    private String icon;

    @Schema(description = "分类编码")
    private String category;

    @Schema(description = "发布所在空间ID；系统广场发布（scope=Tenant）时为空")
    private Long spaceId;

    @Schema(description = "发布范围：Tenant-租户系统广场；Space-团队空间")
    private String scope;

    @Schema(description = "智能体子类型：Single/ChatBot/TaskAgent/PageApp 等")
    private String targetSubType;

    @Schema(description = "智能体类型（取自发布扩展字段 ext.agentType，可能为空）")
    private String agentType;

    @Schema(description = "适用场景，可能为空")
    private List<UsageScenarioEnum> usageScenarios;

    @Schema(description = "是否允许复制：1 允许")
    private Integer allowCopy;

    @Schema(description = "访问控制：0 不管控；1 需数据权限（本接口已按 userId 过滤掉无权访问的）")
    private Integer accessControl;

    @Schema(description = "发布记录创建时间")
    private Date created;

    @Schema(description = "发布记录更新时间")
    private Date modified;
}
