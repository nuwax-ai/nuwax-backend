package com.xspaceagi.pay.web.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.pay.domain.model.PayOrderModel;
import com.xspaceagi.pay.sdk.support.PayOrderExtKeys;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.ProjectTypeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.userapp.sdk.IUserProjectRpcService;
import com.xspaceagi.userapp.sdk.dto.UserProjectDto;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.net.InetAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 开放支付（/api/v1/pay/open）接入身份与归属校验。
 *
 * OpenApi AK：哪个用户
 * 请求体 appId：哪个应用
 */
@Slf4j
@Component
public class OpenPayAccessChecker {

    /** 商户订单号：字母数字-_，1-64 位（拼入平台业务单号，防注入） */
    private static final Pattern OUT_TRADE_NO_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    @Resource
    private IUserProjectRpcService userProjectRpcService;

    @Resource
    private SpacePermissionService spacePermissionService;

    /**
     * 接入应用查询，AK 用户需要有项目空间权限。
     * appType 非空时按 (类型, 业务ID) 查 user_project（refId 路由，用户只需传 user_app.id 等业务 ID）；
     * appType 为空时按 user_project 主键 dbId 查（向后兼容）。
     * 两种方式都做租户一致 + 空间成员校验。
     */
    public UserProjectDto checkAppAccess(String appId, String appType) {
        Assert.hasText(appId, "appId must not be blank");
        UserProjectDto project;
        if (appType != null && !appType.isBlank()) {
            ProjectTypeEnum type;
            try {
                type = ProjectTypeEnum.valueOf(appType.trim());
            } catch (IllegalArgumentException e) {
                throw openAppNotFound();
            }
            long id;
            try {
                id = Long.parseLong(appId.trim());
            } catch (NumberFormatException e) {
                throw openAppNotFound();
            }
            // UserApp 有引用表（ref_id = user_app.id），按 ref 查；其余类型无引用表（ref_id=null），URL 里的 ID 就是主键
            if (type == ProjectTypeEnum.UserApp) {
                project = userProjectRpcService.queryByRef(type, id);
            } else {
                project = userProjectRpcService.queryById(id);
                // 确认类型匹配（防止其他类型误命中）
                if (project != null && project.getProjectType() != type) {
                    project = null;
                }
            }
        } else {
            // 未传 appType：按 dbId 查
            long id;
            try {
                id = Long.parseLong(appId.trim());
            } catch (NumberFormatException e) {
                throw openAppNotFound();
            }
            project = userProjectRpcService.queryById(id);
        }
        if (project == null) {
            throw openAppNotFound();
        }
        Long ctxTenantId = RequestContext.get() != null ? RequestContext.get().getTenantId() : null;
        if (ctxTenantId == null || !ctxTenantId.equals(project.getTenantId())) {
            throw openAppNotFound();
        }
        Long userId = RequestContext.get().getUserId();
        try {
            spacePermissionService.checkSpaceUserPermission(project.getSpaceId(), userId);
        } catch (Exception e) {
            log.warn("[pay-open] app access denied, appId={}, userId={}, spaceId={}", appId, userId, project.getSpaceId());
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.pay_open_app_no_permission);
        }
        return project;
    }

    /**
     * 便捷方法：校验并返回规范 ID（dbId 字符串）——订单号/ext/归属校验统一用它，
     * 与请求用的是业务 ID 还是 dbId 无关
     */
    public String checkAppAccessAndGetCanonicalId(String appId, String appType) {
        return String.valueOf(checkAppAccess(appId, appType).getDbId());
    }

    /**
     * 构建开放订单 ext：记录接入应用 id、项目创建者（收益计入）与 webhook 通知地址。
     */
    public String buildOpenExt(String appId, String appType, String notifyUrl) {
        UserProjectDto project = checkAppAccess(appId, appType);
        Map<String, Object> ext = new HashMap<>();
        ext.put(PayOrderExtKeys.OPEN_PROJECT_ID, String.valueOf(project.getDbId()));
        // 收益计入：复用 GENERAL_PROJECT_* 键，PayOrderBillNotifySupport.addGeneralProjectRevenue
        // 读这两个键将 PAID 金额写入 bill_revenue_detail（targetType=Project, targetId=dbId）
        if (project.getCreatorId() != null) {
            ext.put(PayOrderExtKeys.GENERAL_PROJECT_USER_ID, String.valueOf(project.getCreatorId()));
            ext.put(PayOrderExtKeys.GENERAL_PROJECT_ID, String.valueOf(project.getDbId()));
        }
        if (notifyUrl != null && !notifyUrl.isBlank()) {
            ext.put(PayOrderExtKeys.OPEN_NOTIFY_URL, validateNotifyUrl(notifyUrl.trim()));
        }
        return JSON.toJSONString(ext);
    }

    /**
     * 订单归属校验：订单 ext 的 openProjectId 必须等于本次请求的 appId，否则抛：订单不存在
     */
    public void checkOrderOwnership(PayOrderModel order, String appId) {
        if (order == null || !appId.trim().equals(readExtField(order.getExt(), PayOrderExtKeys.OPEN_PROJECT_ID))) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.pay_open_order_not_found);
        }
    }

    /**
     * 平台业务单号：OPEN_{appId}_{outTradeNo}（outTradeNo 必传幂等键，格式强校验）
     */
    public String buildBizOrderNo(String appId, String outTradeNo) {
        if (outTradeNo == null || !OUT_TRADE_NO_PATTERN.matcher(outTradeNo).matches()) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.pay_open_out_trade_no_invalid);
        }
        return "OPEN_" + appId.trim() + "_" + outTradeNo;
    }

/**
     * notifyUrl 防 SSRF：仅 http(s) + 拒绝平台内网/环回/链路本地/私有网段——
     * 平台以自身出口 POST 该地址，控制台元数据 169.254.x.x / 内网 10.x 等在此硬拒
     */
    private String validateNotifyUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw notifyUrlRejected("must be http(s)");
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                throw notifyUrlRejected("missing host");
            }
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                    || addr.isAnyLocalAddress() || addr.isMulticastAddress()) {
                throw notifyUrlRejected("internal address not allowed");
            }
            // CGNAT 100.64.0.0/10 + IPv6 ULA fc00::/7（与 OpenPayNotifyService.isInternalAddress 同口径）
            byte[] raw = addr.getAddress();
            if (raw.length == 4 && (raw[0] & 0xFF) == 100 && (raw[1] & 0xC0) == 64) {
                throw notifyUrlRejected("CGNAT address not allowed");
            }
            if (raw.length == 16 && (raw[0] & 0xFE) == 0xFC) {
                throw notifyUrlRejected("IPv6 ULA address not allowed");
            }
            return url;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw notifyUrlRejected("malformed URL");
        }
    }

    private BizException notifyUrlRejected(String reason) {
        return BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty,
                "notifyUrl rejected: " + reason);
    }

    /** 从订单 ext JSON 中取字段值 */
    public String readExtField(String ext, String key) {
        if (ext == null || ext.isBlank()) {
            return null;
        }
        try {
            JSONObject obj = JSON.parseObject(ext);
            Object value = obj == null ? null : obj.get(key);
            return value != null ? String.valueOf(value) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private BizException openAppNotFound() {
        return BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.pay_open_app_not_found);
    }
}
