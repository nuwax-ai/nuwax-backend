package com.xspaceagi.agent.core.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xspaceagi.agent.core.adapter.application.ConfigHistoryApplicationService;
import com.xspaceagi.agent.core.adapter.dto.ConfigHistoryDto;
import com.xspaceagi.agent.core.adapter.repository.ConfigHistoryRepository;
import com.xspaceagi.agent.core.adapter.repository.entity.ConfigHistory;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.spec.utils.I18nUtil;
import jakarta.annotation.Resource;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ConfigHistoryApplicationServiceImpl implements ConfigHistoryApplicationService {

    @Resource
    private ConfigHistoryRepository configHistoryRepository;

    @Resource
    private UserApplicationService userApplicationService;

    @Override
    public List<ConfigHistoryDto> queryConfigHistoryList(Published.TargetType targetType, Long targetId) {
        Assert.notNull(targetId, "targetId must be non-null");
        Assert.notNull(targetType, "targetType must be non-null");
        QueryWrapper<ConfigHistory> queryWrapper = new QueryWrapper<>(ConfigHistory.builder().targetType(targetType).targetId(targetId).build());
        queryWrapper.orderByDesc("id");
        List<ConfigHistory> agentConfigHistoryList = configHistoryRepository.list(queryWrapper);

        //查询user信息，并根据id作为key转map
        Map<Long, UserDto> userMap = userApplicationService.queryUserListByIds(agentConfigHistoryList.stream()
                        .map(ConfigHistory::getOpUserId).collect(Collectors.toList())).stream()
                .collect(Collectors.toMap(UserDto::getId, userDto -> userDto));

        return agentConfigHistoryList.stream().map(agentConfigHistory -> {
            ConfigHistoryDto agentConfigHistoryDto = new ConfigHistoryDto();
            BeanUtils.copyProperties(agentConfigHistory, agentConfigHistoryDto);
            agentConfigHistoryDto.setConfig(null);
            UserDto userDto = userMap.get(agentConfigHistory.getOpUserId());
            //userDto转成opUser
            if (userDto != null) {
                agentConfigHistoryDto.setOpUser(ConfigHistoryDto.OpUser.builder().userName(userDto.getUserName())
                        .nickName(userDto.getNickName()).avatar(userDto.getAvatar()).userId(userDto.getId()).build());
            }
            if (agentConfigHistoryDto.getType() == ConfigHistory.Type.Publish) {
                agentConfigHistoryDto.setDescription(I18nUtil.systemMessage("ConfigHistory.publish"));
            }
            return agentConfigHistoryDto;
        }).collect(Collectors.toList());
    }

    @Override
    public ConfigHistoryDto queryConfigHistory(Long id) {
        ConfigHistory configHistory = configHistoryRepository.getById(id);
        if (configHistory == null) {
            return null;
        }
        ConfigHistoryDto configHistoryDto = new ConfigHistoryDto();
        BeanUtils.copyProperties(configHistory, configHistoryDto);
        return configHistoryDto;
    }
}
