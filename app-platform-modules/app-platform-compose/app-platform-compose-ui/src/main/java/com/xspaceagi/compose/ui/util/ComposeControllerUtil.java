package com.xspaceagi.compose.ui.util;

import java.util.Map;
import java.util.Objects;

import com.xspaceagi.compose.sdk.enums.DefaultTableFieldEnum;
import com.xspaceagi.system.spec.common.UserContext;

import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Compose UI 控制器相关的工具类
 */
@Slf4j
@NoArgsConstructor
public class ComposeControllerUtil {

    /**
     * 使用用户信息丰富（或覆盖）rowData，并移除其中的主键ID和其它系统管理字段。
     * UID 必须存在，会覆盖。
     * USER_NAME 和 NICK_NAME 如果在 UserContext 中存在（非null），则覆盖；如果为null，则从 rowData 中移除。
     * ID, AGENT_ID, AGENT_NAME, CREATED 字段会从 rowData 中移除，防止用户传递。
     * 直接修改传入的 rowData Map。
     * 
     * @param rowData     业务数据Map，将被修改。
     * @param userContext 当前用户上下文。
     */
    public static void enrichRowDataWithUserInfo(Map<String, Object> rowData, UserContext userContext) {
        if (rowData == null || userContext == null) {
            log.warn("enrichRowDataWithUserInfo: rowData or userContext null, skip.");
            return;
        }

        log.debug("Process rowData (user fields / system fields). UserContext: {}, rowData: {}", userContext, rowData);

        // UID: 必须存在，直接设置（或覆盖）
        String uid = null;
        if(Objects.isNull(userContext.getUid() )){
            uid = userContext.getUserId().toString();
        } else   {
            uid = userContext.getUid();
        }
        rowData.put(DefaultTableFieldEnum.UID.getFieldName(), uid);

        // USER_NAME: 如果非空则设置，如果为空则移除
        String userName = userContext.getUserName();
        if (userName != null) {
            rowData.put(DefaultTableFieldEnum.USER_NAME.getFieldName(), userName);
        } else {
            rowData.remove(DefaultTableFieldEnum.USER_NAME.getFieldName());
            log.debug("userName null, removed key: {}",
                    DefaultTableFieldEnum.USER_NAME.getFieldName());
        }

        // NICK_NAME: 如果非空则设置，如果为空则移除
        String nickName = userContext.getNickName();
        if (nickName != null) {
            rowData.put(DefaultTableFieldEnum.NICK_NAME.getFieldName(), nickName);
        } else {
            rowData.remove(DefaultTableFieldEnum.NICK_NAME.getFieldName());
            log.debug("nickName null, removed key: {}",
                    DefaultTableFieldEnum.NICK_NAME.getFieldName());
        }

        // 移除系统管理的字段，防止用户传递
        removeSystemField(rowData, DefaultTableFieldEnum.ID);
        removeSystemField(rowData, DefaultTableFieldEnum.AGENT_ID);
        removeSystemField(rowData, DefaultTableFieldEnum.AGENT_NAME);
        removeSystemField(rowData, DefaultTableFieldEnum.CREATED);
        removeSystemField(rowData, DefaultTableFieldEnum.MODIFIED);

        log.debug("Processed rowData: {}", rowData);
    }

    /**
     * 从 rowData 中移除指定的系统字段，并记录日志。
     */
    private static void removeSystemField(Map<String, Object> rowData, DefaultTableFieldEnum fieldEnum) {
        if (fieldEnum == null)
            return;
        String fieldName = fieldEnum.getFieldName();
        if (rowData.containsKey(fieldName)) {
            Object removedValue = rowData.remove(fieldName);
            log.debug("Removed system field '{}' from rowData, value: {}", fieldName, removedValue);
        }
    }
}