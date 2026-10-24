package com.xspaceagi.userapp.domain.service.impl;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.userapp.domain.model.UserPrivateServerModel;
import com.xspaceagi.userapp.domain.repository.IUserAppRepository;
import com.xspaceagi.userapp.domain.repository.IUserPrivateServerRepository;
import com.xspaceagi.userapp.domain.service.IUserPrivateServerDomainService;
import com.xspaceagi.userapp.sdk.dto.UserPrivateServerStatus;

import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class UserPrivateServerDomainServiceImpl implements IUserPrivateServerDomainService {

    /** apiKey 合法格式：8-128 位 RFC 3986 unreserved 字符（字母/数字/`.`/`_`/`~`/`-`），保证可安全置于 x-api-key 请求头 */
    private static final Pattern API_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._~-]{8,128}$");


    @Resource
    private IUserPrivateServerRepository userPrivateServerRepository;
    @Resource
    private IUserAppRepository userAppRepository;

    @Override
    public UserPrivateServerModel register(UserPrivateServerModel model) {
        // 归属字段仅登记时要求
        if (model == null || model.getTenantId() == null || model.getOwnerUserId() == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappPrivateServerInvalidParam);
        }
        validateBase(model);
        // 唯一性不变量：同属主下 host 唯一
        boolean duplicated = userPrivateServerRepository
                .listByOwner(model.getTenantId(), model.getOwnerUserId()).stream()
                .anyMatch(existing -> model.getHost().equalsIgnoreCase(existing.getHost()));
        if (duplicated) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappPrivateServerDuplicate);
        }
        model.setApiKey(UUID.randomUUID().toString().replace("-", ""));
        // docker 环境由用户自行安装：登记后未验证，健康探测通过转 Online
        model.setStatus(UserPrivateServerStatus.Unverified);
        try {
            model.setId(userPrivateServerRepository.add(model));
        } catch (DuplicateKeyException e) {
            // 并发登记同 host：应用层查重的兜底
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappPrivateServerDuplicate);
        }
        log.info("[UserPrivateServer] registered, id={}, owner={}, host={}, agentPort={}",
                model.getId(), model.getOwnerUserId(), model.getHost(), model.getAgentPort());
        return model;
    }

    @Override
    public UserPrivateServerModel update(Long operatorUserId, UserPrivateServerModel model) {
        UserPrivateServerModel existing = requireOwned(model.getId(), operatorUserId);
        validateBase(model);
        boolean hostChanged = !StringUtils.equalsIgnoreCase(existing.getHost(), model.getHost());
        if (hostChanged) {
            // 与 register 维护同一唯一性不变量：同属主下 host 唯一（排除自身）
            boolean duplicated = userPrivateServerRepository
                    .listByOwner(existing.getTenantId(), existing.getOwnerUserId()).stream()
                    .anyMatch(other -> !other.getId().equals(existing.getId())
                            && model.getHost().equalsIgnoreCase(other.getHost()));
            if (duplicated) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappPrivateServerDuplicate);
            }
        }
        // 地址/协议/任一端口变更视为换服务器：回到未验证，健康探测通过前不可用于部署
        boolean addressChanged = hostChanged
                || !StringUtils.equalsIgnoreCase(existing.getScheme(), model.getScheme())
                || !Objects.equals(existing.getAgentPort(), model.getAgentPort())
                || !Objects.equals(existing.getVncPort(), model.getVncPort())
                || !Objects.equals(existing.getFileServerPort(), model.getFileServerPort());
        existing.setName(model.getName());
        existing.setScheme(model.getScheme());
        existing.setHost(model.getHost());
        existing.setAgentPort(model.getAgentPort());
        existing.setVncPort(model.getVncPort());
        existing.setFileServerPort(model.getFileServerPort());
        // apiKey 可选覆盖：传入非空才更新（地址未变，不触发重置未验证状态；健康探测本身不携带 key）
        boolean apiKeyChanged = StringUtils.isNotBlank(model.getApiKey())
                && !Objects.equals(existing.getApiKey(), model.getApiKey().trim());
        if (apiKeyChanged) {
            existing.setApiKey(model.getApiKey().trim());
        }
        if (addressChanged) {
            existing.setStatus(UserPrivateServerStatus.Unverified);
        }
        // 稀疏更新：仅写本次可变字段（status 仅地址变更时携带；apiKey 仅变更时携带），不整行写回覆盖并发的状态修改
        UserPrivateServerModel update = new UserPrivateServerModel();
        update.setId(existing.getId());
        update.setName(existing.getName());
        update.setScheme(existing.getScheme());
        update.setHost(existing.getHost());
        update.setAgentPort(existing.getAgentPort());
        update.setVncPort(existing.getVncPort());
        update.setFileServerPort(existing.getFileServerPort());
        if (apiKeyChanged) {
            update.setApiKey(existing.getApiKey());
        }
        if (addressChanged) {
            update.setStatus(existing.getStatus());
        }
        userPrivateServerRepository.updateById(update);
        log.info("[UserPrivateServer] updated, id={}, operator={}, addressChanged={}, apiKeyChanged={}",
                model.getId(), operatorUserId, addressChanged, apiKeyChanged);
        return existing;
    }

    @Override
    public void delete(Long operatorUserId, Long id) {
        requireOwned(id, operatorUserId);
        if (userAppRepository.existsByPrivateServerId(id)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappPrivateServerInUse);
        }
        userPrivateServerRepository.deleteById(id);
        log.info("[UserPrivateServer] deleted, id={}, operator={}", id, operatorUserId);
    }

    @Override
    public List<UserPrivateServerModel> listByOwner(Long tenantId, Long ownerUserId) {
        return userPrivateServerRepository.listByOwner(tenantId, ownerUserId);
    }

    @Override
    public UserPrivateServerModel getById(Long id) {
        return userPrivateServerRepository.getById(id);
    }

    @Override
    public void markStatus(Long id, boolean online) {
        UserPrivateServerModel model = userPrivateServerRepository.getById(id);
        if (model == null) {
            return;
        }
        UserPrivateServerStatus target = online ? UserPrivateServerStatus.Online : UserPrivateServerStatus.Offline;
        if (model.getStatus() == target) {
            // 状态未变仍要刷新检查时间
            userPrivateServerRepository.updateLastHealthCheck(id, new Date());
            return;
        }
        // 状态翻转：稀疏更新状态与检查时间，不整行写回（避免覆盖并发 update 的字段）
        userPrivateServerRepository.updateStatus(id, target, new Date());
        log.info("[UserPrivateServer] status -> {}, id={}, host={}", target.getValue(), id, model.getHost());
    }

    @Override
    public void saveVersion(Long id, String version) {
        userPrivateServerRepository.updateVersion(id, version);
        log.info("[UserPrivateServer] version saved, id={}, version={}", id, version);
    }

    private UserPrivateServerModel requireOwned(Long id, Long operatorUserId) {
        UserPrivateServerModel model = userPrivateServerRepository.getById(id);
        if (model == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappPrivateServerNotFound);
        }
        if (!Objects.equals(model.getOwnerUserId(), operatorUserId)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappPrivateServerNoPermission);
        }
        return model;
    }

    private void validateBase(UserPrivateServerModel model) {
        if (model == null || StringUtils.isBlank(model.getName())
                || StringUtils.isBlank(model.getHost())
                || model.getAgentPort() == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappPrivateServerInvalidParam);
        }
        if (!"http".equalsIgnoreCase(model.getScheme()) && !"https".equalsIgnoreCase(model.getScheme())) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappPrivateServerInvalidParam);
        }
        // agentPort 必填；vncPort/fileServerPort 可选（null=未设置），传了须在合法区间
        if (!isValidPort(model.getAgentPort())
                || (model.getVncPort() != null && !isValidPort(model.getVncPort()))
                || (model.getFileServerPort() != null && !isValidPort(model.getFileServerPort()))) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappPrivateServerInvalidParam);
        }
        // apiKey 经 x-api-key 请求头明文传输：仅允许 HTTP 头安全字符（RFC 3986 unreserved 子集），
        // 防非 ASCII/空白/CR-LF 导致下游 4xx 或 header 注入；平台生成值为 32 位 hex 天然满足
        if (StringUtils.isNotBlank(model.getApiKey())
                && !API_KEY_PATTERN.matcher(model.getApiKey().trim()).matches()) {
            throw new BizException(BizExceptionCodeEnum.userappPrivateServerInvalidParam.getCode(),
                    "apiKey 仅支持 8-128 位字母、数字及 . _ ~ - 字符（不能包含空格/中文/特殊符号）");
        }
        model.setScheme(model.getScheme().toLowerCase());
        model.setHost(model.getHost().trim());
    }

    private static boolean isValidPort(Integer port) {
        return port != null && port >= 1 && port <= 65535;
    }
}
