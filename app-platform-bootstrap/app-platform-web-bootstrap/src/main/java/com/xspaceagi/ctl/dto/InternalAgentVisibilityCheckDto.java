package com.xspaceagi.ctl.dto;

import java.io.Serializable;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 内部调用接口「智能体可见性判定」的结果。
 * <p>
 * 只回一个 id 子集，不回明细：调用方问的是「哪些能用」，判定依据（发布记录、可见空间、
 * 数据权限）留在平台侧，不对外暴露——也就不会随着平台内部口径调整而变成调用方依赖。
 */
@Data
public class InternalAgentVisibilityCheckDto implements Serializable {

    @Schema(description = "可见（=该用户能用）的智能体ID子集，按请求里的顺序排列；"
            + "未发布 / 发布到该用户不可见的空间 / 受数据权限管控且未授权的一律不在其中。"
            + "请求里的 id 若不存在或属于别的租户，同样不会出现")
    private List<Long> visibleAgentIds;
}
