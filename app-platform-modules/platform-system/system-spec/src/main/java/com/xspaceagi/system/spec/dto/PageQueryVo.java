package com.xspaceagi.system.spec.dto;


import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * <p>
 * 分页查询VO
 * </p>
 *
 */
@Schema(description = "分页查询VO")
public class PageQueryVo<T> {

     @Schema(description =  "分页查询过滤条件")
    private T queryFilter;

    @NotNull(message = "pageNo is required")
     @Schema(description =  "分页pageNo", required = true, example = "1")
    private Long pageNo;

    @NotNull(message = "pageSize is required")
     @Schema(description =  "分页pageSize", required = true, example = "10")
    private Long pageSize;

    public T getQueryFilter() {
        return queryFilter;
    }

    public void setQueryFilter(T queryFilter) {
        this.queryFilter = queryFilter;
    }

    public Long getPageNo() {
        return pageNo;
    }

    public void setPageNo(Long pageNo) {
        this.pageNo = pageNo;
    }

    public Long getPageSize() {
        return pageSize;
    }

    public void setPageSize(Long pageSize) {
        this.pageSize = pageSize;
    }
}
