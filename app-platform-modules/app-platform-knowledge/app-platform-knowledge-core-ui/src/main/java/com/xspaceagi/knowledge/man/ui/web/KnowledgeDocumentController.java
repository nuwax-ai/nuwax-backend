package com.xspaceagi.knowledge.man.ui.web;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xspaceagi.knowledge.core.application.service.IKnowledgeConfigApplicationService;
import com.xspaceagi.knowledge.core.application.service.IKnowledgeDocumentApplicationService;
import com.xspaceagi.knowledge.core.spec.utils.ThreadTenantUtil;
import com.xspaceagi.knowledge.domain.model.KnowledgeDocumentModel;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeDocumentRepository;
import com.xspaceagi.knowledge.man.ui.web.base.BaseController;
import com.xspaceagi.knowledge.man.ui.web.dto.config.KnowledgeConfigDeleteRequest;
import com.xspaceagi.knowledge.man.ui.web.dto.document.*;
import com.xspaceagi.system.application.dto.SpaceDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.application.service.SysDataPermissionApplicationService;
import com.xspaceagi.system.domain.log.LogPrint;
import com.xspaceagi.system.domain.log.LogRecordPrint;
import com.xspaceagi.system.infra.service.QueryVoListDelegateService;
import com.xspaceagi.system.sdk.operate.ActionType;
import com.xspaceagi.system.sdk.operate.OperationLogReporter;
import com.xspaceagi.system.sdk.operate.SystemEnum;
import com.xspaceagi.system.sdk.service.dto.UserDataPermissionDto;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.page.PageQueryParamVo;
import com.xspaceagi.system.spec.page.PageQueryVo;
import com.xspaceagi.system.spec.page.SuperPage;
import com.xspaceagi.system.spec.tenant.thread.TenantRunnable;
import com.xspaceagi.system.spec.utils.FileAkUtil;
import com.xspaceagi.system.spec.utils.ValidateUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.web.bind.annotation.*;

import java.beans.IntrospectionException;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Objects;

import static com.xspaceagi.system.spec.enums.ResourceEnum.COMPONENT_LIB_MODIFY;
import static com.xspaceagi.system.spec.enums.ResourceEnum.COMPONENT_LIB_QUERY_DETAIL;

@Tag(name = "知识库-文档配置接口")
@RestController
@RequestMapping("/api/knowledge/document")
@Slf4j
public class KnowledgeDocumentController extends BaseController {

    @Resource
    private QueryVoListDelegateService queryVoListDelegateService;

    @Resource
    private IKnowledgeDocumentApplicationService knowledgeDocumentApplicationService;

    @Resource
    private IKnowledgeDocumentRepository knowledgeDocumentRepository;

    @Resource
    private ThreadTenantUtil threadTenantUtil;
    @Resource
    private SpaceApplicationService spaceApplicationService;

    @Resource
    private FileAkUtil fileUrl;

    //新增内容
    @Resource
    private SysDataPermissionApplicationService sysDataPermissionApplicationService;

    //新增内容
    @Resource
    private IKnowledgeConfigApplicationService knowledgeConfigApplicationService;

    @RequireResource(COMPONENT_LIB_QUERY_DETAIL)
    @OperationLogReporter(actionType = ActionType.QUERY, action = "数据列表查询", objectName = "知识库文档", systemCode = SystemEnum.KNOWLEDGE_CONFIG)
    @LogPrint(step = "知识库[知识库文档]-数据列表查询")
    @LogRecordPrint(content = "[知识库文档]-数据列表查询")
    @Operation(summary = "数据列表查询")
    @RequestMapping(path = "/list", method = RequestMethod.POST)
    public ReqResult<IPage<KnowledgeDocumentVo>> list(
            @RequestBody PageQueryVo<KnowledgeDocumentQueryRequest> pageQueryVo) {
        var userContext = this.getUser();
        var userId = userContext.getUserId();
        var filter = pageQueryVo.getQueryFilter();
        pageQueryVo.setQueryFilter(filter);

        PageQueryParamVo pageQueryParamVo = new PageQueryParamVo(pageQueryVo);

        if (!isAdmin()) {
            // 查询用户有权限的空间,限制访问空间,比如工作流查询全部知识库,要限制用户有权限的空间下的知识库
            var spaceList = this.spaceApplicationService.queryListByUserId(userId);
            var spaceIds = spaceList.stream().map(SpaceDto::getId)
                    .toList();
            pageQueryParamVo.getQueryMap().put("authSpaceIds", spaceIds);
        }

        SuperPage<KnowledgeDocumentModel> superPage = this.queryVoListDelegateService.queryVoList(
                this.knowledgeDocumentRepository,
                pageQueryParamVo, null);

        var userModelList = superPage.getRecords();

        // 类型转换
        List<KnowledgeDocumentVo> userDtoList = userModelList.stream()
                .map(KnowledgeDocumentVo::convert2Dto)
                .toList();
        SuperPage<KnowledgeDocumentVo> iPage = SuperPage.build(superPage, userDtoList);

        return ReqResult.success(iPage);
    }

    @RequireResource(COMPONENT_LIB_QUERY_DETAIL)
    @OperationLogReporter(actionType = ActionType.QUERY, action = "查询文档状态", objectName = "知识库文档", systemCode = SystemEnum.KNOWLEDGE_CONFIG)
    @LogPrint(step = "知识库[知识库文档]-查询文档状态")
    @LogRecordPrint(content = "[知识库文档]-查询文档状态")
    @Operation(summary = "查询文档状态")
    @RequestMapping(path = "/queryDocStatus", method = RequestMethod.POST)
    public ReqResult<List<KnowledgeDocumentVo>> queryDocStatus(
            @RequestBody KnowledgeDocumentQueryStatusRequest request) {

        ValidateUtil.validateThrowIfException(request);

        var docIds = request.getDocIds();

        var userContext = getUser();
        var docStatusList = knowledgeDocumentApplicationService.queryDocStatus(docIds, userContext);

        var docStatusVoList = docStatusList.stream()
                .map(KnowledgeDocumentVo::convert2Dto)
                .toList();

        return ReqResult.success(docStatusVoList);
    }

    /**
     * 数据删除接口（需要权限）
     *
     * @param request
     * @return
     * @throws IntrospectionException
     * @throws InvocationTargetException
     * @throws IllegalAccessException
     */
    @RequireResource(COMPONENT_LIB_MODIFY)
    @OperationLogReporter(actionType = ActionType.DELETE, action = "数据删除接口", objectName = "知识库文档", systemCode = SystemEnum.KNOWLEDGE_CONFIG)
    @LogPrint(step = "知识库[知识库文档]-数据删除接口")
    @Operation(summary = "数据删除接口")
    @RequestMapping(path = "/deleteById", method = RequestMethod.GET)
    public ReqResult<Void> delete(KnowledgeConfigDeleteRequest request)
            throws IntrospectionException, InvocationTargetException, IllegalAccessException {

        ValidateUtil.validateThrowIfException(request);

        var id = request.getId();

        var userContext = getUser();
        knowledgeDocumentApplicationService.deleteById(id, userContext);
        return ReqResult.success();

    }

    /**
     *
     * @param dataId
     * @return
     * @throws IntrospectionException
     * @throws InvocationTargetException
     * @throws IllegalAccessException
     */
    @RequireResource(COMPONENT_LIB_QUERY_DETAIL)
    @OperationLogReporter(actionType = ActionType.QUERY, action = "数据详情查询", objectName = "知识库文档", systemCode = SystemEnum.KNOWLEDGE_CONFIG)
    @LogPrint(step = "知识库[知识库文档]-数据详情查询")
    @Operation(summary = "数据详情查询")
    @RequestMapping(path = "/detailById", method = RequestMethod.GET)
    public ReqResult<KnowledgeDocumentVo> detailById(@Schema(description = "数据ID") Long dataId)
            throws IntrospectionException, InvocationTargetException, IllegalAccessException {

        var model = knowledgeDocumentApplicationService.queryOneInfoById(dataId);
        var knowledgeDocumentVo = KnowledgeDocumentVo.convert2Dto(model);
        return ReqResult.success(knowledgeDocumentVo);

    }

    @RequireResource(COMPONENT_LIB_MODIFY)
    @OperationLogReporter(actionType = ActionType.MODIFY, action = "数据更新接口", objectName = "知识库文档", systemCode = SystemEnum.KNOWLEDGE_CONFIG)
    @LogPrint(step = "知识库[知识库文档]-数据更新接口")
    @Operation(summary = "数据更新接口")
    @RequestMapping(path = "/update", method = RequestMethod.POST)
    public ReqResult<Long> update(@RequestBody KnowledgeDocumentUpdateRequest updateDto)
            throws IntrospectionException, InvocationTargetException, IllegalAccessException {
        if (Objects.nonNull(updateDto.getSegmentConfig())) {
            ValidateUtil.validateThrowIfException(updateDto.getSegmentConfig());
        }

        var userContext = this.getUser();

        var model = KnowledgeDocumentUpdateRequest.convert2Model(updateDto);

        var id = knowledgeDocumentApplicationService.updateInfo(model, userContext);
        return ReqResult.success(id);

    }

    @RequireResource(COMPONENT_LIB_MODIFY)
    @OperationLogReporter(actionType = ActionType.MODIFY, action = "更改文件名称", objectName = "知识库文档", systemCode = SystemEnum.KNOWLEDGE_CONFIG)
    @LogPrint(step = "知识库[知识库文档]-更改文件名称")
    @Operation(summary = "更改文件名称")
    @RequestMapping(path = "/updateDocName", method = RequestMethod.POST)
    public ReqResult<Long> updateDocName(@RequestBody KnowledgeDocumentUpdateNameRequest updateDto) {
        ValidateUtil.validateThrowIfException(updateDto);

        var userContext = this.getUser();
        var docId = updateDto.getDocId();
        var name = updateDto.getName();
        var id = knowledgeDocumentApplicationService.updateDocName(docId, name, userContext);
        return ReqResult.success(id);

    }

    @RequireResource(COMPONENT_LIB_MODIFY)
    @OperationLogReporter(actionType = ActionType.ADD, action = "数据新增接口", objectName = "知识库文档", systemCode = SystemEnum.KNOWLEDGE_CONFIG)
    @LogPrint(step = "知识库[知识库文档]-数据新增接口")
    @Operation(summary = "数据新增接口", description = "新增数据")
    @RequestMapping(path = "/add", method = RequestMethod.POST)
    public ReqResult<List<Long>> add(@RequestBody KnowledgeDocumentAddRequest addDto)
            throws IntrospectionException, InvocationTargetException, IllegalAccessException {

        if (CollectionUtils.isNotEmpty(addDto.getFileList())) {
            addDto.getFileList().forEach(fileInfoVo -> fileInfoVo.setDocUrl(fileUrl.getFileUrlWithAk(fileInfoVo.getDocUrl())));
        }

        if (Objects.nonNull(addDto.getSegmentConfig())) {
            ValidateUtil.validateThrowIfException(addDto.getSegmentConfig());
        }

        var userContext = this.getUser();

        //新增权限内容
        //知识库大小验证

        long submitFileSize = 0;
        if (addDto != null) {
            //List<FileInfoVo>
            List<FileInfoVo> fileInfoVoList = addDto.getFileList();
            if (fileInfoVoList != null) {
                for (FileInfoVo vo : fileInfoVoList) {
                    submitFileSize = submitFileSize + vo.getFileSize();
                }
            }
        }

        UserDataPermissionDto userDataPermissions = sysDataPermissionApplicationService.getUserDataPermission(userContext.getUserId());
        //System.out.println("=========userDataPermissions>>1");
        if (userDataPermissions != null && userDataPermissions.getKnowledgeStorageLimitGb() != null && userDataPermissions.getKnowledgeStorageLimitGb().doubleValue() != -1D) {
            //System.out.println("=========userDataPermissions>>2");
            //KnowledgeConfigModel model = knowledgeConfigRepository.queryOneInfoById(id);
            long id = addDto.getKbId();
            var model = knowledgeConfigApplicationService.queryOneInfoById(id);
            if (model != null && model.getFileSize() != null) {
                //System.out.println("=========userDataPermissions>>3");
                Long fileSize = model.getFileSize() + submitFileSize;
                Double gbSize = fileSize / (1024.0 * 1024 * 1024);
                //System.out.println("LimitGb："+userDataPermissions.getKnowledgeStorageLimitGb() + ",gbSize:" + gbSize);
                if (userDataPermissions.getKnowledgeStorageLimitGb().doubleValue() <= gbSize) {
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.knowledgeStorageUpperBound,
                            userDataPermissions.getKnowledgeStorageLimitGb());
                }
            }
        }
        //新增权限内容

        var model = KnowledgeDocumentAddRequest.convert2Model(addDto);

        var ids = knowledgeDocumentApplicationService.batchAddInfo(model, userContext);
        return ReqResult.success(ids);

    }

    @RequireResource(COMPONENT_LIB_MODIFY)
    @OperationLogReporter(actionType = ActionType.ADD, action = "自定义新增接口", objectName = "知识库文档", systemCode = SystemEnum.KNOWLEDGE_CONFIG)
    @LogPrint(step = "知识库[知识库文档]-自定义新增接口")
    @Operation(summary = "自定义新增接口", description = "手动新增文本内容")
    @RequestMapping(path = "/customAdd", method = RequestMethod.POST)
    public ReqResult<Long> customAdd(@RequestBody KnowledgeDocumentCustomAddRequest addDto)
            throws IntrospectionException, InvocationTargetException, IllegalAccessException {

        if (Objects.nonNull(addDto.getSegmentConfig())) {
            ValidateUtil.validateThrowIfException(addDto.getSegmentConfig());
        }

        var userContext = this.getUser();
        var model = KnowledgeDocumentCustomAddRequest.convert2ModelForCustom(addDto);

        var ids = knowledgeDocumentApplicationService.customAddInfo(model, userContext);
        return ReqResult.success(ids);

    }

    @RequireResource(COMPONENT_LIB_MODIFY)
    @OperationLogReporter(actionType = ActionType.MODIFY, action = "生成文档Q&A", objectName = "知识库文档", systemCode = SystemEnum.KNOWLEDGE_CONFIG)
    @LogPrint(step = "知识库[知识库文档]-生成文档Q&A")
    @Operation(summary = "生成文档Q&A")
    @RequestMapping(path = "/generateQA/{docId}", method = RequestMethod.POST)
    public ReqResult<Void> generateQAs(@PathVariable Long docId) {
        var userContext = this.getUser();

        knowledgeDocumentApplicationService.generateForQa(docId, userContext);
        return ReqResult.success();
    }

    @RequireResource(COMPONENT_LIB_MODIFY)
    @OperationLogReporter(actionType = ActionType.MODIFY, action = "生成嵌入", objectName = "知识库文档", systemCode = SystemEnum.KNOWLEDGE_CONFIG)
    @LogPrint(step = "知识库[知识库文档]-生成嵌入")
    @Operation(summary = "生成嵌入")
    @RequestMapping(path = "/doc/generateEmbeddings/{docId}", method = RequestMethod.POST)
    public ReqResult<Void> generateEmbeddings(@PathVariable Long docId) {
        var userContext = this.getUser();

        knowledgeDocumentApplicationService.generateEmbeddings(docId, userContext);
        return ReqResult.success();
    }

    @OperationLogReporter(actionType = ActionType.MODIFY, action = "生成嵌入", objectName = "重试最近x天的失败任务,如果有分段,问答,向量化有失败的话", systemCode = SystemEnum.KNOWLEDGE_CONFIG)
    @LogPrint(step = "知识库[知识库文档]-重试最近x天的失败任务,如果有分段,问答,向量化有失败的话")
    @Operation(summary = "重试最近x天的失败任务,如果有分段,问答,向量化有失败的话", description = "入参:days,表示对最近{days}天的任务有效")
    @RequestMapping(path = "/doc/retryAllTaskByDays/{days}", method = RequestMethod.GET)
    public ReqResult<Void> retryAllTaskByDocId(@PathVariable Integer days) {
        var userContext = this.getUser();

        knowledgeDocumentApplicationService.autoRetryTaskByDays(days, userContext);
        return ReqResult.success();
    }

    @OperationLogReporter(actionType = ActionType.MODIFY, action = "生成嵌入", objectName = "根据文件id,自动重试,如果有分段,问答,向量化有失败的话", systemCode = SystemEnum.KNOWLEDGE_CONFIG)
    @LogPrint(step = "知识库[知识库文档]-根据文件id,自动重试,如果有分段,问答,向量化有失败的话")
    @Operation(summary = "根据文件id,自动重试,如果有分段,问答,向量化有失败的话", description = "只对最近3天的任务有效")
    @RequestMapping(path = "/doc/autoRetryTaskByDocId/{docId}", method = RequestMethod.GET)
    public ReqResult<Void> autoRetryTaskByDocId(@PathVariable Long docId) {
        var userContext = this.getUser();
        var userId = userContext.getUserId();

        UserDto userDto = (UserDto) RequestContext.get().getUser();
        TenantRunnable tenantRunnable = new TenantRunnable(() -> {
            RequestContext.setThreadTenantId(userDto.getTenantId());
            RequestContext.get().setUser(userDto);
            Long tenantId = RequestContext.get().getTenantId();
            RequestContext.get().setUserId(userId);
            log.info("tenantId: {}, userId: {}", tenantId, userId);
            knowledgeDocumentApplicationService.retryAllTaskByDocId(docId, userContext);
        });

        threadTenantUtil.obtainCommonExecutor().execute(tenantRunnable);

        return ReqResult.success();
    }

}
