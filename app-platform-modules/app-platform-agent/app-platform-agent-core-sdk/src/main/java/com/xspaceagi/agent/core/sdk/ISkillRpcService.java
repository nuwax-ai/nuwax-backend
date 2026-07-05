package com.xspaceagi.agent.core.sdk;

import com.xspaceagi.agent.core.spec.enums.UsageScenarioEnum;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface ISkillRpcService {

    /**
     * 导入技能（支持文件或URL导入）
     *
     * @param url            技能文件URL（与file二选一）
     * @param file           上传的技能文件（与url二选一）
     * @param targetSkillId  目标技能ID（导入到已有技能），为null时创建新技能
     * @param targetSpaceId  目标空间ID（创建新技能时必填）
     * @param usageScenarios 使用场景列表
     * @return 技能ID
     */
    Long importSkill(String url, MultipartFile file, Long targetSkillId, Long targetSpaceId, List<UsageScenarioEnum> usageScenarios);

}