package com.xspaceagi.knowledge.man.ui.web;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xspaceagi.agent.core.adapter.application.WorkflowApplicationService;
import com.xspaceagi.knowledge.core.application.service.IKnowledgeConfigApplicationService;
import com.xspaceagi.knowledge.domain.model.KnowledgeConfigModel;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeConfigRepository;
import com.xspaceagi.knowledge.domain.vectordb.VectorDBService;
import com.xspaceagi.knowledge.man.ui.web.base.BaseController;
import com.xspaceagi.knowledge.man.ui.web.dto.config.*;
import com.xspaceagi.system.application.dto.SpaceDto;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.application.service.SysDataPermissionApplicationService;
import com.xspaceagi.system.application.util.DefaultIconUrlUtil;
import com.xspaceagi.system.domain.log.LogPrint;
import com.xspaceagi.system.infra.service.QueryVoListDelegateService;
import com.xspaceagi.system.sdk.operate.ActionType;
import com.xspaceagi.system.sdk.operate.OperationLogReporter;
import com.xspaceagi.system.sdk.operate.SystemEnum;
import com.xspaceagi.system.sdk.service.dto.UserDataPermissionDto;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.page.PageQueryParamVo;
import com.xspaceagi.system.spec.page.PageQueryVo;
import com.xspaceagi.system.spec.page.SuperPage;
import com.xspaceagi.system.spec.utils.ValidateUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.xspaceagi.system.spec.enums.ResourceEnum.*;

@Tag(name = "知识库-基础配置接口")
@RestController
@RequestMapping("/api/knowledge/config")
@Slf4j
public class KnowledgeConfigController extends BaseController {

    @Resource
    private QueryVoListDelegateService queryVoListDelegateService;

    @Resource
    private IKnowledgeConfigApplicationService knowledgeConfigApplicationService;

    @Resource
    private IKnowledgeConfigRepository knowledgeConfigRepository;

    @Resource
    private VectorDBService vectorDBService;

    @Resource
    private SpaceApplicationService spaceApplicationService;


    @Resource
    private WorkflowApplicationService workflowApplicationService;

    @Resource
    private SysDataPermissionApplicationService sysDataPermissionApplicationService;

    /**
     * 知识库的列表查询接口，合并为详情的权限（需要权限）
     *
     * @param pageQueryVo
     * @return
     */
    @RequireResource(COMPONENT_LIB_QUERY_DETAIL)
    @OperationLogReporter(actionType = ActionType.QUERY, action = "数据列表查询", objectName = "知识库基础配置", systemCode = SystemEnum.KNOWLEDGE_CONFIG)
    @LogPrint(step = "知识库[知识库基础配置]-数据列表查询")
    @Operation(summary = "数据列表查询")
    @RequestMapping(path = "/list", method = RequestMethod.POST)
    public ReqResult<IPage<KnowledgeConfigVo>> list(
            @RequestBody PageQueryVo<KnowledgeConfigQueryRequest> pageQueryVo) {
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

        SuperPage<KnowledgeConfigModel> superPage = this.queryVoListDelegateService.queryVoList(
                this.knowledgeConfigRepository,
                pageQueryParamVo, null);

        var userModelList = superPage.getRecords();

        // 类型转换
        List<KnowledgeConfigVo> userDtoList = userModelList.stream()
                .map(KnowledgeConfigVo::convert2Dto)
                .toList();
        SuperPage<KnowledgeConfigVo> iPage = SuperPage.build(superPage, userDtoList);
        iPage.setCurrent(pageQueryVo.getCurrent());
        iPage.setSize(pageQueryVo.getPageSize());

        return ReqResult.success(iPage);
    }

    /**
     * 知识库的删除接口（需要权限）
     *
     * @param request
     * @return
     */
    @RequireResource(COMPONENT_LIB_DELETE)
    @OperationLogReporter(actionType = ActionType.DELETE, action = "数据删除接口", objectName = "知识库基础配置", systemCode = SystemEnum.KNOWLEDGE_CONFIG)
    @LogPrint(step = "知识库[知识库基础配置]-数据删除接口")
    @Operation(summary = "数据删除接口")
    @RequestMapping(path = "/deleteById", method = RequestMethod.GET)
    public ReqResult<Void> delete(KnowledgeConfigDeleteRequest request) {

        var id = request.getId();
        var userContext = this.getUser();
        knowledgeConfigApplicationService.deleteById(id, userContext);
        return ReqResult.success();

    }

    /**
     * 知识库的详情查询，合并为详情的权限（需要权限）
     *
     * @param id
     * @return
     */
    @RequireResource(COMPONENT_LIB_QUERY_DETAIL)
    @OperationLogReporter(actionType = ActionType.QUERY, action = "数据详情查询", objectName = "知识库基础配置", systemCode = SystemEnum.KNOWLEDGE_CONFIG)
    @LogPrint(step = "知识库[知识库基础配置]-数据详情查询")
    @Operation(summary = "数据详情查询")
    @RequestMapping(path = "/detailById", method = RequestMethod.GET)
    public ReqResult<KnowledgeConfigVo> detailById(@Schema(description = "知识库ID") Long id) {

        var model = knowledgeConfigApplicationService.queryOneInfoById(id);
        var knowledgeConfigVo = KnowledgeConfigVo.convert2Dto(model);
        if (knowledgeConfigVo != null) {
            knowledgeConfigVo.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(knowledgeConfigVo.getIcon(), knowledgeConfigVo.getName(), "knowledge"));

            var workflowId = model.getWorkflowId();
            if (workflowId != null) {
                var workflowConfigDto = workflowApplicationService.queryById(workflowId);
                if (workflowConfigDto != null) {
                    var workflowName = workflowConfigDto.getName();
                    knowledgeConfigVo.setWorkflowName(workflowName);
                    knowledgeConfigVo.setWorkflowDescription(workflowConfigDto.getDescription());
                    String workflowIcon = DefaultIconUrlUtil.setDefaultIconUrl(workflowConfigDto.getIcon(), workflowName);
                    knowledgeConfigVo.setWorkflowIcon(workflowIcon);
                }
            }
        }
        return ReqResult.success(knowledgeConfigVo);

    }

    /**
     * 知识库的更新操作（需要权限）
     *
     * @param updateDto
     * @return
     */
    @RequireResource(COMPONENT_LIB_MODIFY)
    @OperationLogReporter(actionType = ActionType.MODIFY, action = "数据更新接口", objectName = "知识库基础配置", systemCode = SystemEnum.KNOWLEDGE_CONFIG)
    @LogPrint(step = "知识库[知识库基础配置]-数据更新接口")
    @Operation(summary = "数据更新接口")
    @RequestMapping(path = "/update", method = RequestMethod.POST)
    public ReqResult<Long> update(@RequestBody KnowledgeConfigUpdateRequest updateDto) {

        var userContext = this.getUser();
        var model = KnowledgeConfigUpdateRequest.convert2Model(updateDto);
        var id = knowledgeConfigApplicationService.updateInfo(model, userContext);
        return ReqResult.success(id);

    }

    /**
     * 添加权限 知识库的新增操作（需要权限）
     *
     * @param addDto
     * @return
     */
    @RequireResource(COMPONENT_LIB_CREATE)
    @OperationLogReporter(actionType = ActionType.ADD, action = "数据新增接口", objectName = "知识库基础配置", systemCode = SystemEnum.KNOWLEDGE_CONFIG)
    @LogPrint(step = "知识库[知识库基础配置]-数据新增接口")
    @Operation(summary = "数据新增接口", description = "新增数据")
    @RequestMapping(path = "/add", method = RequestMethod.POST)
    public ReqResult<Long> add(@RequestBody KnowledgeConfigAddRequest addDto) {
        var userContext = this.getUser();

        //新增权限内容
        UserDataPermissionDto userDataPermissions = sysDataPermissionApplicationService.getUserDataPermission(userContext.getUserId());
        //知识库上限验证
        if (userDataPermissions != null) {
            //Integer maxKnowledgeCount = userDataPermissions.getMaxKnowledgeCount();
            //Integer knowledgeStorageLimitGb = userDataPermissions.getKnowledgeStorageLimitGb();
            Map<String, Object> queryMap = new HashMap<>();
            //queryMap.put("creator_id", userContext.getUserId());
            queryMap.put("creatorId", userContext.getUserId());
            Long currentKnowledgeTotal = knowledgeConfigRepository.queryTotal(queryMap);
            //System.out.println("currentKnowledgeTotal:" + currentKnowledgeTotal + ",Permissions_KnowledgeTotal" + userDataPermissions.getMaxKnowledgeCount());
            if (userDataPermissions.getMaxKnowledgeCount() != null && userDataPermissions.getMaxKnowledgeCount() != -1 && userDataPermissions.getMaxKnowledgeCount() <= currentKnowledgeTotal) {
                //throw new BizException("操作失败，添加的知识库数量超过上限！（目前最多只能创建" + userDataPermissions.getMaxKnowledgeCount() + "个知识库");
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.knowledgeKbCreateCountExceeded,
                        userDataPermissions.getMaxKnowledgeCount());
            }
        }
        //新增权限内容

        // 校验参数
        ValidateUtil.validateThrowIfException(addDto);

        var model = KnowledgeConfigAddRequest.convert2Model(addDto);
        var id = knowledgeConfigApplicationService.addInfo(model, userContext);
        return ReqResult.success(id);
    }

    @RequireResource(COMPONENT_LIB_MODIFY)
    @OperationLogReporter(actionType = ActionType.ADD, action = "补偿初始化向量数据库", objectName = "知识库基础配置", systemCode = SystemEnum.KNOWLEDGE_CONFIG)
    @LogPrint(step = "知识库[知识库基础配置]-补偿初始化向量数据库")
    @Operation(summary = "补偿初始化向量数据库", description = "补偿初始化向量数据库")
    @RequestMapping(path = "/initVectorDb", method = RequestMethod.POST)
    public ReqResult<Long> add(Long id) {

        var model = this.knowledgeConfigApplicationService.queryOneInfoById(id);
        Long embeddingModelId = null;
        if (model != null) {
            embeddingModelId = model.getEmbeddingModelId();
        }
        // 初始化向量数据库
        vectorDBService.initAndCheckCollection(id, embeddingModelId);
        return ReqResult.success(id);
    }

}
