package com.xspaceagi.system.application.service;

import java.util.List;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xspaceagi.system.application.dto.SensitiveWordCheckResultDto;
import com.xspaceagi.system.application.dto.SensitiveWordCreateDto;
import com.xspaceagi.system.application.dto.SensitiveWordQueryDto;
import com.xspaceagi.system.application.dto.SensitiveWordStatusDto;
import com.xspaceagi.system.application.dto.SensitiveWordUpdateDto;
import com.xspaceagi.system.infra.dao.entity.SensitiveWord;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.page.PageQueryVo;

/**
 * 敏感词管控应用服务
 */
public interface SensitiveWordApplicationService {

    /**
     * 创建敏感词
     */
    ReqResult<SensitiveWord> create(SensitiveWordCreateDto dto, UserContext userContext);

    /**
     * 更新敏感词
     */
    ReqResult<SensitiveWord> update(SensitiveWordUpdateDto dto, UserContext userContext);

    /**
     * 删除敏感词
     */
    ReqResult<Void> delete(Long id, UserContext userContext);

    /**
     * 启用/禁用敏感词
     */
    ReqResult<Void> updateStatus(SensitiveWordStatusDto dto, UserContext userContext);

    /**
     * 分页查询敏感词
     */
    Page<SensitiveWord> pageQuery(PageQueryVo<SensitiveWordQueryDto> pageQueryVo);

    /**
     * 按条件查询当前租户全部敏感词列表（不分页）
     */
    List<SensitiveWord> listAll(SensitiveWordQueryDto dto);

    /**
     * 查询当前租户已启用的敏感词列表
     */
    List<SensitiveWord> listEnabled(Long tenantId);

    /**
     * 检测文本，返回命中的敏感词及按策略处理后的文本
     */
    SensitiveWordCheckResultDto checkText(Long tenantId, String text);
}
