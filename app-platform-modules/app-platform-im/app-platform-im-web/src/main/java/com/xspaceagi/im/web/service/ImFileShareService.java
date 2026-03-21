package com.xspaceagi.im.web.service;

import com.xspaceagi.agent.core.infra.rpc.UserShareRpcService;
import com.xspaceagi.system.sdk.service.dto.UserShareDto;
import com.xspaceagi.system.spec.common.RequestContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;

/**
 * IM 文件分享服务
 */
@Service
@Slf4j
public class ImFileShareService {

    @Resource
    private UserShareRpcService userShareRpcService;

    private static final Integer expireSeconds = 60 * 60;

    /**
     * 创建文件分享
     *
     * @param userId        用户 ID
     * @param tenantId      租户 ID
     * @param conversationId 会话 ID
     * @param filePath      文件路径
     * @return shareKey，创建失败返回 null
     */
    public String createFileShare(Long userId, Long conversationId, String filePath, Long tenantId) {
        if (userId == null || tenantId == null || StringUtils.isBlank(filePath)) {
            log.warn("创建文件分享失败：userId、tenantId 或 filePath 为空");
            return null;
        }

        if (conversationId == null) {
            log.warn("创建文件分享失败：conversationId 为空");
            return null;
        }

        // 设置 RequestContext 的租户 ID
        RequestContext.setThreadTenantId(tenantId);

        try {
            UserShareDto userShareDto = new UserShareDto();
            userShareDto.setType(UserShareDto.UserShareType.CONVERSATION);
            userShareDto.setTargetId(conversationId.toString());
            userShareDto.setUserId(userId);
            userShareDto.setContent(filePath);
            userShareDto.setExpire(Date.from(Instant.now().plusSeconds(expireSeconds)));

            UserShareDto result = userShareRpcService.addOrUpdateUserShare(userShareDto);
            if (result != null && StringUtils.isNotBlank(result.getShareKey())) {
                return result.getShareKey();
            } else {
                log.warn("文件分享创建失败：返回结果为空或 shareKey 为空");
                return null;
            }
        } catch (Exception e) {
            log.error("创建文件分享异常: userId={}, conversationId={}, filePath={}", userId, conversationId, filePath, e);
            return null;
        } finally {
            // 清理 RequestContext
            RequestContext.remove();
        }
    }

}
