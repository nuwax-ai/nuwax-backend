package com.xspaceagi.im.wechat.ilink.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

@Data
public class GetUpdatesResp {
    private Integer ret;
    private Integer errcode;
    private String errmsg;
    /** 部分网关返回拼写为 errmsgh */
    @JSONField(name = "errmsgh")
    private String errmsgh;
    private List<WeixinMessage> msgs;
    @Deprecated
    @JSONField(name = "sync_buf")
    private String syncBuf;
    @JSONField(name = "get_updates_buf")
    private String getUpdatesBuf;
    @JSONField(name = "longpolling_timeout_ms")
    private Long longpollingTimeoutMs;

    public String resolveErrmsg() {
        if (StringUtils.isNotBlank(errmsg)) {
            return errmsg;
        }
        return errmsgh;
    }
}
