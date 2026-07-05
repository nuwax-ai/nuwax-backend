package com.xspaceagi.agent.core.infra.component.model.dto;

import com.xspaceagi.agent.core.adapter.dto.config.PageArgConfig;
import com.xspaceagi.agent.core.adapter.dto.config.bind.CardBindConfigDto;
import com.xspaceagi.agent.core.infra.component.ArgExtractUtil;
import com.xspaceagi.agent.core.spec.enums.BindCardStyleEnum;
import com.xspaceagi.agent.core.spec.enums.ComponentTypeEnum;
import com.xspaceagi.agent.core.spec.enums.ExecuteStatusEnum;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class ComponentExecutingDto implements Serializable {

    private Long targetId;

    private String name;

    // 通用智能体工具执行原始标题
    private String originalTitle;

    private ComponentTypeEnum type;

    private ExecuteStatusEnum status;

    private Object executingMessage;

    private ComponentExecuteResult result;

    private CardBindConfigDto cardBindConfig;

    private Object cardData;

    private PageArgConfig pageArgConfig;

    private SubEventTypeEnum subEventType;

    //获取卡片数据
    public Object getCardData() {
        if (cardBindConfig != null && result.getData() != null && cardBindConfig.getCardArgsBindConfigs() != null) {
            if (cardBindConfig.getBindCardStyle() == BindCardStyleEnum.LIST) {
                String bindArray = cardBindConfig.getBindArray();
                if (bindArray == null) {
                    cardBindConfig = null;
                    return null;
                }
                Object value = ArgExtractUtil.extraBindValue(result.getData(), bindArray);
                if (value == null || !(value instanceof List)) {
                    cardBindConfig = null;
                    return null;
                }
                List<?> dataList = (List<?>) value;
                List<Map<String, Object>> itemList = new ArrayList<>();
                for (Object item : dataList) {
                    Map<String, Object> detail = new HashMap<>();
                    itemList.add(detail);
                    cardBindConfig.getCardArgsBindConfigs().forEach(cardArgsBindConfig -> {
                        String bindValue = cardArgsBindConfig.getBindValue();
                        if (StringUtils.isBlank(bindValue)) {
                            return;
                        }
                        bindValue = cardArgsBindConfig.getBindValue().replaceFirst("^" + bindArray + "\\.", "");
                        Object value1 = ArgExtractUtil.extraBindValue(item, bindValue);
                        detail.put(cardArgsBindConfig.getKey(), value1);
                    });
                    if (StringUtils.isNotBlank(cardBindConfig.getBindLinkUrl())) {
                        String bindLink = cardBindConfig.getBindLinkUrl().replaceFirst("^" + bindArray + "\\.", "");
                        Object linkUrl = ArgExtractUtil.extraBindValue(item, bindLink);
                        if (linkUrl != null) {
                            detail.put("bindLinkUrl", linkUrl);
                        }
                    }
                }
                cardData = itemList;
                if (itemList.isEmpty()) {
                    cardBindConfig = null;
                    return null;
                }
            }
            if (cardBindConfig.getBindCardStyle() == BindCardStyleEnum.SINGLE && result.getData() != null) {
                Map<String, Object> data = new HashMap<>();
                cardBindConfig.getCardArgsBindConfigs().forEach(cardArgsBindConfig -> {
                    if (StringUtils.isBlank(cardArgsBindConfig.getBindValue())) {
                        return;
                    }
                    Object value = ArgExtractUtil.extraBindValue(result.getData(), cardArgsBindConfig.getBindValue());
                    if (value instanceof List) {
                        value = ((List<?>) value).get(0);
                    }
                    data.put(cardArgsBindConfig.getKey(), value);
                });
                if (StringUtils.isNotBlank(cardBindConfig.getBindLinkUrl())) {
                    String linkUrl = ArgExtractUtil.extraBindValue(result.getData(), cardBindConfig.getBindLinkUrl()).toString();
                    data.put("bindLinkUrl", linkUrl);
                }
                cardData = data;
                if (data.isEmpty()) {
                    cardBindConfig = null;
                    return null;
                }
            }
        }

        return cardData;
    }

    public enum SubEventTypeEnum {
        // 打开桌面
        OPEN_DESKTOP,
        // 打开文件
        OPEN_FILE,
        // request_permission
        REQUEST_PERMISSION,
        //ASK USER
        ASK_QUESTION,
    }
}
