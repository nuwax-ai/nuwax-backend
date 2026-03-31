package com.xspaceagi.log.web.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xspaceagi.log.sdk.request.DocumentSearchRequest;
import com.xspaceagi.log.sdk.service.ISearchRpcService;
import com.xspaceagi.log.sdk.vo.LogDocument;
import com.xspaceagi.log.sdk.vo.SearchResult;
import com.xspaceagi.log.web.controller.base.BaseController;
import com.xspaceagi.log.web.controller.dto.LogDetailQueryDto;
import com.xspaceagi.log.web.controller.dto.LogQueryDto;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.exception.LogPlatformException;
import com.xspaceagi.system.spec.page.PageQueryVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.xspaceagi.system.spec.enums.ResourceEnum.SYSTEM_RUNNING_LOG_QUERY_DETAIL;
import static com.xspaceagi.system.spec.enums.ResourceEnum.SYSTEM_RUNNING_LOG_QUERY_LIST;

@Tag(name = "日志平台管理端-搜索接口")
@RestController
@RequestMapping("/api/system/requestLogs")
@Slf4j
public class LogQueryManController extends BaseController {

    @Resource
    private ISearchRpcService iSearchRpcService;

    @RequireResource({SYSTEM_RUNNING_LOG_QUERY_LIST})
    @Operation(summary = "统一日志查询")
    @RequestMapping(path = "/list", method = RequestMethod.POST)
    public ReqResult<IPage<LogDocument>> search(@RequestBody PageQueryVo<LogQueryDto> pageQueryVo) {
        return search0(pageQueryVo);
    }

    @RequireResource({SYSTEM_RUNNING_LOG_QUERY_DETAIL})
    @Operation(summary = "日志详情")
    @RequestMapping(path = "/detail", method = RequestMethod.POST)
    public ReqResult<LogDocument> detail(@RequestBody LogDetailQueryDto logDetailQueryDto) {

        DocumentSearchRequest.DocumentSearchRequestBuilder builder = DocumentSearchRequest.builder();
        builder.searchDocumentClazz(LogDocument.class);
        List<Map<String, Object>> filterFieldsAndValues = new ArrayList<>();
        builder.filterFieldsAndValues(filterFieldsAndValues);
        if (StringUtils.isNoneBlank(logDetailQueryDto.getId())) {
            filterFieldsAndValues.add(Map.of("id", logDetailQueryDto.getId()));
        }

        SearchResult search = iSearchRpcService.search(builder.build());
        LogDocument logDocument = search.getItems().stream().map(item -> (LogDocument) item.getDocument()).findFirst().orElse(null);
        if (logDocument == null) {
            return ReqResult.error("日志不存在");
        }
        return ReqResult.success(logDocument);
    }

}
