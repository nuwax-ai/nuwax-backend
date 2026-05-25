package com.xspaceagi.pay.sdk.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PageResult<T> {
    private List<T> records;
    private long total;
    /** 当前页，从 1 开始 */
    private long page;
    private long pageSize;
}
