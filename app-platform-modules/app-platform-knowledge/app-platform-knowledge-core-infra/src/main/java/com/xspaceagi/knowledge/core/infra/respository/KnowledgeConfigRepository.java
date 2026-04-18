package com.xspaceagi.knowledge.core.infra.respository;

import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.xspaceagi.knowledge.core.infra.dao.entity.KnowledgeConfig;
import com.xspaceagi.knowledge.core.infra.dao.mapper.KnowledgeConfigMapper;
import com.xspaceagi.knowledge.core.infra.dao.service.KnowledgeConfigService;
import com.xspaceagi.knowledge.core.infra.dao.service.KnowledgeDocumentService;
import com.xspaceagi.knowledge.core.infra.dao.service.KnowledgeQaSegmentService;
import com.xspaceagi.knowledge.core.infra.dao.service.KnowledgeRawSegmentService;
import com.xspaceagi.knowledge.core.infra.translator.IKnowledgeConfigTranslator;
import com.xspaceagi.knowledge.core.spec.utils.ThreadTenantUtil;
import com.xspaceagi.knowledge.domain.model.KnowledgeConfigModel;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeConfigRepository;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.KnowledgeException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Repository
@Slf4j
public class KnowledgeConfigRepository implements IKnowledgeConfigRepository {

    @Resource
    private IKnowledgeConfigTranslator knowledgeConfigTranslator;

    @Resource
    private KnowledgeConfigService knowledgeConfigService;

    @Resource
    private KnowledgeConfigMapper knowledgeConfigMapper;

    @Resource
    private KnowledgeDocumentService knowledgeDocumentService;

    @Resource
    private KnowledgeQaSegmentService knowledgeQaSegmentService;

    @Resource
    private KnowledgeRawSegmentService knowledgeRawSegmentService;


    @Resource
    private ThreadTenantUtil threadTenantUtil;

    @Override
    public List<KnowledgeConfigModel> pageQuery(Map<String, Object> queryMap, List<OrderItem> orderColumns,
                                                Long startIndex, Long pageSize) {

        var dataList = this.knowledgeConfigMapper.queryList(queryMap,
                orderColumns, startIndex, pageSize);
        var ans = dataList.stream()
                .map(sysUser -> this.knowledgeConfigTranslator.convertToModel(sysUser))
                .collect(Collectors.toList());

        return ans;
    }

    @Override
    public Long queryTotal(Map<String, Object> queryMap) {
        return this.knowledgeConfigMapper.queryTotal(queryMap);
    }

    @Override
    public KnowledgeConfigModel queryOneInfoById(Long id) {
        var data = this.knowledgeConfigService.queryOneInfoById(id);
        var ans = this.knowledgeConfigTranslator.convertToModel(data);
        if (Objects.isNull(ans)) {
            return null;
        }
        // 查询计算文档的总文件大小, 这里走缓存,在文档的:新增/修改/删除,也会自动触发更新知识库的文件大小预估值
        return ans;
    }

    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public void deleteById(Long id) {

        var existObj = this.knowledgeConfigService.getById(id);
        if (existObj == null) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }

        this.knowledgeConfigService.removeById(id);

        // 删除关联的文档,文档分段等数据
        this.knowledgeDocumentService.deleteByConfigyId(id);
        this.knowledgeQaSegmentService.deleteByConfigId(id);
        this.knowledgeRawSegmentService.deleteByConfigId(id);

    }

    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public Long updateInfo(KnowledgeConfigModel model, UserContext userContext) {
        var existObj = this.knowledgeConfigService.queryOneInfoById(model.getId());
        if (Objects.isNull(existObj)) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }

        model.setCreatorId(null);
        model.setCreatorName(null);
        model.setModified(null);
        model.setModifiedId(userContext.getUserId());
        model.setModifiedName(userContext.getUserName());
        var entity = this.knowledgeConfigTranslator.convertToEntity(model);
        var id = this.knowledgeConfigService.updateInfo(entity);

        return id;
    }

    @Override
    public Long addInfo(KnowledgeConfigModel model, UserContext userContext) {
        model.setId(null);
        model.setCreatorId(userContext.getUserId());
        model.setCreatorName(userContext.getUserName());

        var entity = this.knowledgeConfigTranslator.convertToEntity(model);
        var id = this.knowledgeConfigService.addInfo(entity);

        return id;
    }

    @Override
    public List<KnowledgeConfigModel> queryListBySpaceId(Long spaceId) {
        var dataList = this.knowledgeConfigService.queryListBySpaceId(spaceId);
        return dataList.stream()
                .map(knowledgeConfigTranslator::convertToModel)
                .toList();
    }

    @Override
    public void updateLatestModifyTime(Long id, UserContext userContext) {
        this.knowledgeConfigService.updateLatestModifyTime(id, userContext);

    }

    @Override
    public void updateKnowledgeConfigFileSize(Long kbId, UserContext userContext) {

        var fileSize = this.knowledgeDocumentService.queryTotalFileSize(kbId);

        this.knowledgeConfigService.updateKnowledgeConfigFileSize(kbId, fileSize);
    }

    @Override
    public Long queryTotalFileSize(Long kbId) {
        return this.knowledgeDocumentService.queryTotalFileSize(kbId);
    }

    @Override
    public List<KnowledgeConfigModel> queryListByIds(List<Long> kbIds) {
        var dataList = this.knowledgeConfigService.queryListByIds(kbIds);
        return dataList.stream()
                .map(knowledgeConfigTranslator::convertToModel)
                .toList();
    }

    @Override
    public List<Long> queryAllKbIds() {
        return this.knowledgeConfigMapper.queryAllKbIds();
    }

    @Override
    public Long querySpaceIdByKbId(Long kbId) {
        var config = this.knowledgeConfigService.queryOneInfoById(kbId);
        return config != null ? config.getSpaceId() : null;
    }

    @Override
    public List<KnowledgeConfigModel> queryUnsyncedKnowledgeBases(Long tenantId) {
        //20251210,改为查询所有的知识库，通过分段 rawText 的同步标记，来判断是否需要同步，这里不再主动过滤知识库的同步标记
        var dataList = this.knowledgeConfigMapper.queryUnsyncedKnowledgeBases(tenantId);
        return dataList.stream()
                .map(knowledgeConfigTranslator::convertToModel)
                .collect(Collectors.toList());
    }

    @Override
    public void updateFulltextSyncStatus(Long kbId, Integer status, Long segmentCount) {
        // 先查询出当前的modified时间,防止自动更新
        var existObj = this.knowledgeConfigService.queryOneInfoById(kbId);
        var currentModified = existObj != null ? existObj.getModified() : null;
        this.knowledgeConfigMapper.updateFulltextSyncStatus(kbId, status, segmentCount, currentModified);
    }

    @Override
    public void updateFulltextSyncStatus(Long kbId, Integer status) {
        // 先查询出当前的modified时间,防止自动更新
        var existObj = this.knowledgeConfigService.queryOneInfoById(kbId);
        var currentModified = existObj != null ? existObj.getModified() : null;
        this.knowledgeConfigMapper.updateFulltextSyncStatusWithoutSegmentCount(kbId, status, currentModified);
    }

    @Override
    public Map<Integer, Long> querySyncStatusStats(Long tenantId) {
        return this.knowledgeConfigMapper.querySyncStatusStats(tenantId);
    }

    @Override
    public Long countTotalKnowledge(Long userId) {
        LambdaQueryWrapper<KnowledgeConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(userId != null, KnowledgeConfig::getCreatorId, userId);
        return this.knowledgeConfigService.count(queryWrapper);
    }
}
