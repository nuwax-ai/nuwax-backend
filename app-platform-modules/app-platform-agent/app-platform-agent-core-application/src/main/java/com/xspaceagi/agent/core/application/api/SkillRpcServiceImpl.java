package com.xspaceagi.agent.core.application.api;

import com.xspaceagi.agent.core.adapter.application.SkillApplicationService;
import com.xspaceagi.agent.core.sdk.ISkillRpcService;
import com.xspaceagi.agent.core.spec.enums.UsageScenarioEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Slf4j
@Service
public class SkillRpcServiceImpl implements ISkillRpcService {

    @Resource
    private SkillApplicationService skillApplicationService;

    @Override
    public Long importSkill(String url, MultipartFile file, Long targetSkillId, Long targetSpaceId, List<UsageScenarioEnum> usageScenarios) {
        return skillApplicationService.importSkill(url, file, targetSkillId, targetSpaceId, usageScenarios);
    }

}
