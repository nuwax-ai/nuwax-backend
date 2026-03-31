package com.xspaceagi.knowledge.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.common.collect.Lists;
import com.xspaceagi.agent.core.adapter.dto.config.KnowledgeConfigDto;
import com.xspaceagi.knowledge.assembler.KnowledgeModelApiAssembler;
import com.xspaceagi.knowledge.core.application.service.IKnowledgeConfigApplicationService;
import com.xspaceagi.knowledge.core.application.vo.KnowledgeConfigApplicationRequestVo;
import com.xspaceagi.knowledge.core.infra.dao.entity.KnowledgeConfig;
import com.xspaceagi.knowledge.core.infra.dao.service.KnowledgeConfigService;
import com.xspaceagi.knowledge.core.infra.respository.KnowledgeConfigRepository;
import com.xspaceagi.knowledge.core.infra.translator.IKnowledgeConfigTranslator;
import com.xspaceagi.knowledge.domain.model.KnowledgeConfigModel;
import com.xspaceagi.knowledge.domain.service.IKnowledgeConfigDomainService;
import com.xspaceagi.knowledge.sdk.request.KnowledgeConfigRequestVo;
import com.xspaceagi.knowledge.sdk.request.KnowledgeCreateRequestVo;
import com.xspaceagi.knowledge.sdk.response.KnowledgeConfigResponseVo;
import com.xspaceagi.knowledge.sdk.response.KnowledgeConfigVo;
import com.xspaceagi.knowledge.sdk.sevice.IKnowledgeConfigRpcService;
import com.xspaceagi.system.domain.log.LogRecordPrint;
import com.xspaceagi.system.sdk.server.IUserRpcService;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.page.PageQueryVo;
import com.xspaceagi.system.spec.page.SuperPage;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
public class KnowledgeConfigRpcService implements IKnowledgeConfigRpcService {

    @Resource
    private IKnowledgeConfigApplicationService knowledgeConfigApplicationService;

    @Resource
    private IKnowledgeConfigDomainService knowledgeConfigDomainService;

    @Resource
    private KnowledgeConfigService knowledgeConfigService;

    @Resource
    private IKnowledgeConfigTranslator iKnowledgeConfigTranslator;

    @Resource
    private IUserRpcService userRpcService;

    @Override
    public KnowledgeConfigResponseVo queryListKnowledgeConfig(KnowledgeConfigRequestVo knowledgeConfigRequestVo) {
        PageQueryVo<KnowledgeConfigApplicationRequestVo> pageQueryVo = new PageQueryVo<>();

        var current = 1L;
        if (Objects.nonNull(knowledgeConfigRequestVo.getPage())) {
            current = knowledgeConfigRequestVo.getPage();
        }
        var pageSize = 100L;
        if (Objects.nonNull(knowledgeConfigRequestVo.getPageSize())) {
            pageSize = knowledgeConfigRequestVo.getPageSize();
        }

        pageQueryVo.setCurrent(current);
        pageQueryVo.setPageSize(pageSize);
        KnowledgeConfigApplicationRequestVo knowledgeConfigApplicationRequestVo = KnowledgeConfigApplicationRequestVo
                .convert(knowledgeConfigRequestVo);
        pageQueryVo.setQueryFilter(knowledgeConfigApplicationRequestVo);
        var modelPage = knowledgeConfigApplicationService.querySearchConfigs(pageQueryVo);

        var voList = modelPage.getRecords().stream()
                .map(KnowledgeConfigModel::convertFromModel)
                .toList();

        // 补充用户信息,比如:昵称
        var userIds = modelPage.getRecords().stream()
                .map(KnowledgeConfigModel::getCreatorId)
                .filter(Objects::nonNull)
                .toList();

        var userList = this.userRpcService.queryUserListByIds(userIds);
        var userMap = userList.stream()
                .collect(Collectors.toMap(UserContext::getUserId, userContext -> userContext));

        voList.forEach(item -> {
            UserContext userContext = userMap.get(item.getCreatorId());
            if (userContext != null) {
                item.setCreatorName(userContext.getUserName());
                item.setCreatorNickName(userContext.getNickName());
                item.setCreatorAvatar(userContext.getAvatar());
            }
        });

        SuperPage<KnowledgeConfigVo> iPage = SuperPage.build(modelPage, voList);
        iPage.setCurrent(pageQueryVo.getCurrent());
        iPage.setSize(pageQueryVo.getPageSize());

        var response = new KnowledgeConfigResponseVo();
        response.setConfigPage(iPage);
        return response;
    }

    @LogRecordPrint(content = "[知识库文档]-创建知识库")
    @Override
    public Long createKnowledgeConfig(KnowledgeCreateRequestVo createRequestVo) {

        var model = KnowledgeModelApiAssembler.convertFromVo(createRequestVo);

        // 补充用户信息,比如:昵称
        var userId = createRequestVo.getUserId();
        var userIds = Lists.newArrayList(userId);

        var userList = this.userRpcService.queryUserListByIds(userIds);

        var userContext = userList.stream()
                .findFirst()
                .orElse(UserContext.builder()
                        .userId(userId)
                        .userName("未知用户")
                        .nickName("未知用户")
                        .build());
        model.setCreatorName(userContext.getUserName());
        model.setCreatorId(userId);

        var dataId = this.knowledgeConfigDomainService.addInfo(model, userContext);
        return dataId;
    }

    @Override
    public KnowledgeConfigVo queryKnowledgeConfigById(Long id) {
        var model = this.knowledgeConfigDomainService.queryOneInfoById(id);
        return KnowledgeConfigModel.convertFromModel(model);
    }

    @Override
    public Long countTotalKnowledge(Long userId) {
        return knowledgeConfigDomainService.countTotalKnowledge(userId);
    }

    @Override
    public IPage<KnowledgeConfigVo> queryListForManage(Integer pageNo, Integer pageSize, String name, java.util.List<Long> creatorIds, Long spaceId) {
        LambdaQueryWrapper<KnowledgeConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.like(StringUtils.isNotBlank(name), KnowledgeConfig::getName, name);
        queryWrapper.eq(spaceId != null, KnowledgeConfig::getSpaceId, spaceId);
        queryWrapper.in(creatorIds != null && !creatorIds.isEmpty(), KnowledgeConfig::getCreatorId, creatorIds);
        queryWrapper.inSql(KnowledgeConfig::getSpaceId, "SELECT id FROM space where yn=0 ");
        queryWrapper.orderByDesc(KnowledgeConfig::getId);
        return knowledgeConfigService.page(new Page<>(pageNo, pageSize), queryWrapper).convert(knowledgeConfig -> {
            return KnowledgeConfigModel.convertFromModel(iKnowledgeConfigTranslator.convertToModel(knowledgeConfig));
        });
    }

    @Override
    public void deleteForManage(Long id) {
        // 调用领域服务删除
        var model = knowledgeConfigDomainService.queryOneInfoById(id);
        if (model != null) {
            knowledgeConfigDomainService.deleteById(id, com.xspaceagi.system.spec.common.UserContext.builder()
                    .userId(0L)
                    .userName("admin")
                    .tenantId(model.getTenantId())
                    .build());
        }
    }
}
