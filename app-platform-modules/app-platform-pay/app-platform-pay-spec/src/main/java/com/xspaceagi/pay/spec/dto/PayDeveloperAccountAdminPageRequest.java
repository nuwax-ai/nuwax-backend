package com.xspaceagi.pay.spec.dto;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class PayDeveloperAccountAdminPageRequest {

    /** 用户名/昵称模糊关键字；先查用户表再按 userId 过滤开发者账户（与系统用户列表检索规则一致，最多取前 500 条匹配用户） */
    private String userNameKeyword;

    private Long userId;
    private LocalDateTime createdStart;
    private LocalDateTime createdEnd;
    private Integer page;
    private Integer pageSize;
}
