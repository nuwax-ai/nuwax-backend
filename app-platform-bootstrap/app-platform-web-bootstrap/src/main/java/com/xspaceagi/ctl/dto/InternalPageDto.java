package com.xspaceagi.ctl.dto;

import java.io.Serializable;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 内部调用接口的分页返回结构。
 * <p>
 * 不复用平台前端的 {@code SuperPage}：那个结构带 {@code columns}（表格列定义）、
 * {@code orders} 等前端渲染字段，对 server-to-server 调用方是无意义的噪音；
 * 这里只保留 total/page/pageSize/records 四个稳定字段。
 *
 * @param <T> 记录类型
 */
@Data
public class InternalPageDto<T> implements Serializable {

    @Schema(description = "符合条件的总条数（跨全部页）")
    private long total;

    @Schema(description = "当前页码，从 1 开始")
    private long page;

    @Schema(description = "每页条数")
    private long pageSize;

    @Schema(description = "当前页记录，无结果时为 []")
    private List<T> records;

    public static <T> InternalPageDto<T> of(long total, long page, long pageSize, List<T> records) {
        InternalPageDto<T> dto = new InternalPageDto<>();
        dto.setTotal(total);
        dto.setPage(page);
        dto.setPageSize(pageSize);
        dto.setRecords(records == null ? List.of() : records);
        return dto;
    }

    /** 空页（越权/无可见空间等 fail-closed 场景），保留调用方传入的分页参数便于对账 */
    public static <T> InternalPageDto<T> empty(long page, long pageSize) {
        return of(0L, page, pageSize, List.of());
    }
}
