package com.xspaceagi.agent.core.adapter.dto;

import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.spec.enums.UsageScenarioEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PublishedQueryDto implements Serializable {

    @Schema(description = "目标类型，Agent,Plugin,Workflow")
    private Published.TargetType targetType;

    @Schema(description = "目标类型列表（多类型联合查询，如应用列表的网页应用+全栈应用，2026.9月版本添加）")
    private List<Published.TargetType> targetTypes;

    @Schema(description = "子类型")
    private Published.TargetSubType targetSubType;

    @Schema(description = "子类型列表（多类型联合查询时的子类型过滤，如应用列表的PageApp+UserApp）")
    private List<Published.TargetSubType> targetSubTypes;

    @Schema(description = "排除的子类型，如只查智能体、但排除网页应用（Agent 下的 PageApp 子类型）", hidden = true)
    private List<Published.TargetSubType> excludeTargetSubTypes;

    @Schema(description = "搜索范围（可见范围）：Tenant-系统广场（默认）；Space-团队空间（2026.9月版本添加）")
    private Published.PublishScope scope;

    @Schema(description = "智能体类型")
    private List<String> agentTypes;

    @Schema(description = "页码")
    private Integer page;

    @Schema(description = "上一页最后一条数据的时间戳，与page二选一")
    private Long lastTimestamp;

    @Schema(description = "每页数量")
    private Integer pageSize;

    @Schema(description = "分类名称")
    private String category;

    @Schema(description = "关键字搜索")
    private String kw;

    @Schema(description = "空间ID（可选）需要通过空间过滤时有用")
    private Long spaceId;

    @Schema(description = "空间IDs", hidden = true)
    private List<Long> spaceIds;

    @Schema(description = "只返回空间的组件")
    private Boolean justReturnSpaceData;

    @Schema(description = "空间ID列表（可选）,查询用户有权限的空间,限制访问空间,比如工作流查询全部知识库,要限制用户有权限的空间下的知识库", hidden = true)
    private List<Long> authSpaceIds;

    @Schema(description = "是否显示推荐", hidden = true)
    private Boolean showRecommend;

    @Schema(description = "允许复制过滤（模板），1 允许", hidden = true)
    private Integer allowCopy;

    @Schema(description = "只允许模板使用过滤，1 允许", hidden = true)
    private Integer onlyTemplate;

    @Schema(description = "更新统计数据", hidden = true)
    private Boolean updateStatics;

    @Schema(description = "访问控制过滤，0 无需过滤，1 过滤出需要权限管控的内容")
    private Integer accessControl;

    @Schema(description = "查询的ID范围，比如只查看推荐、官方标识的组件等", hidden = true)
    private List<Long> targetIds;

    @Schema(description = "多类型查询时按类型限定targetId集合（内部使用；各类型ID为独立空间，避免跨类型数值误匹配，如官方推荐的网页应用/全栈应用）", hidden = true)
    private Map<Published.TargetType, List<Long>> targetTypeIds;

    @Schema(description = "推荐列表", hidden = true)
    private List<Long> recommendIds;

    @Schema(description = "排除列表", hidden = true)
    private List<Long> excludeIds;

    @Schema(description = "是否只返回官方标识的内容")
    private Boolean official;

    @Schema(description = "适用场景筛选参数，如 [TaskAgent, PageApp]")
    private List<UsageScenarioEnum> usageScenarios;

    @Schema(description = "是否返回配置信息", hidden = true)
    private boolean returnConfig;

    /**
     * 获取页码
     *
     * @return
     */

    public Integer obtainPage() {
        Integer current = this.getPage();
        if (current == null || current <= 0) {
            current = 1;
        }
        return current;
    }

    /**
     * 获取每页数量
     *
     * @return
     */
    public Integer obtainPageSize() {
        Integer size = this.getPageSize();
        if (size == null || size <= 0) {
            size = 10;
        }
        return size;
    }
}
