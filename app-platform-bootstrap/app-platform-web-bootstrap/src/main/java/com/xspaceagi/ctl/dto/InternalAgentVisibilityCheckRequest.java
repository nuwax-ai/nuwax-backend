package com.xspaceagi.ctl.dto;

import java.io.Serializable;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 内部调用接口「智能体可见性判定」的入参。
 * <p>
 * 语义是「以这个用户为视角，这些智能体他能不能用」——所以两件事都必须由调用方给准：
 * 视角是谁（{@code userId}，内部接口没有登录态），以及要判定的对象（{@code agentIds}，
 * 平台原生智能体 id，不是发布记录 id）。
 */
@Data
public class InternalAgentVisibilityCheckRequest implements Serializable {

    @Schema(description = "查看者用户ID（必填）。必须属于调用方声明的租户，否则报用户不存在；"
            + "可见范围以他为视角：该用户所属空间 + 租户系统广场，再按该用户的数据权限收口")
    private Long userId;

    @Schema(description = "待判定的智能体ID列表（平台原生 agentId；去 null 去重后单次上限 50）。"
            + "传空数组/不传 = 不判定任何对象，直接返回空结果，不算参数错误")
    private List<Long> agentIds;
}
