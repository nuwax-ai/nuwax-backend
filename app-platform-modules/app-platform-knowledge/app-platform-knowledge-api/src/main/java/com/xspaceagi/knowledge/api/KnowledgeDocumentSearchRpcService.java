package com.xspaceagi.knowledge.api;

import com.xspaceagi.knowledge.core.spec.enums.KnowledgeDataTypeEnum;
import com.xspaceagi.knowledge.domain.model.KnowledgeDocumentModel;
import com.xspaceagi.knowledge.domain.service.IKnowledgeDocumentDomainService;
import com.xspaceagi.knowledge.domain.service.impl.KnowledgeConfigDomainService;
import com.xspaceagi.knowledge.sdk.enums.KnowledgePubStatusEnum;
import com.xspaceagi.knowledge.sdk.request.KnowledgeDocumentRequestVo;
import com.xspaceagi.knowledge.sdk.response.KnowledgeDocumentResponseVo;
import com.xspaceagi.knowledge.sdk.sevice.IKnowledgeDocumentSearchRpcService;
import com.xspaceagi.knowledge.sdk.vo.DocumentAddRequestVo;
import com.xspaceagi.knowledge.sdk.vo.SegmentConfigModel;
import com.xspaceagi.system.domain.log.LogRecordPrint;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.KnowledgeException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Objects;

@Slf4j
@Service
public class KnowledgeDocumentSearchRpcService implements IKnowledgeDocumentSearchRpcService {

    @Resource
    private IKnowledgeDocumentDomainService knowledgeDocumentDomainService;

    @Resource
    private KnowledgeConfigDomainService knowledgeConfigDomainService;


    @LogRecordPrint(content = "[知识库文档]-根据知识库id查询文档")
    @Override
    public KnowledgeDocumentResponseVo documentSearch(KnowledgeDocumentRequestVo requestVo) {
        var docList = this.knowledgeDocumentDomainService.queryDocByKbId(requestVo.getKbId());
        var ans = docList.stream()
                .map(KnowledgeDocumentModel::convertFromModel)
                .toList();
        KnowledgeDocumentResponseVo responseVo = new KnowledgeDocumentResponseVo();
        responseVo.setDocumentVoList(ans);
        return responseVo;
    }

    @Override
    public Long documentAdd(DocumentAddRequestVo documentAddRequestVo) {
        // 查询基础配置,补全基础信息
        var knowledgeConfig = this.knowledgeConfigDomainService.queryOneInfoById(documentAddRequestVo.getKbId());
        if (Objects.isNull(knowledgeConfig)) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }
        KnowledgeDocumentModel knowledgeDocumentModel = new KnowledgeDocumentModel();
        knowledgeDocumentModel.setTenantId(knowledgeConfig.getTenantId());
        knowledgeDocumentModel.setCreatorId(knowledgeConfig.getCreatorId());
        knowledgeDocumentModel.setCreatorName(knowledgeConfig.getCreatorName());
        knowledgeDocumentModel.setSpaceId(knowledgeConfig.getSpaceId());
        knowledgeDocumentModel.setId(null);
        knowledgeDocumentModel.setKbId(documentAddRequestVo.getKbId());
        knowledgeDocumentModel.setName(documentAddRequestVo.getName());
        knowledgeDocumentModel.setDocUrl(documentAddRequestVo.getDocUrl());
        knowledgeDocumentModel.setPubStatus(KnowledgePubStatusEnum.Waiting);
        knowledgeDocumentModel.setHasQa(Boolean.FALSE);
        knowledgeDocumentModel.setHasEmbedding(Boolean.FALSE);
        SegmentConfigModel segmentConfig = SegmentConfigModel.obtainDefaultModel();
        segmentConfig.setSegment(documentAddRequestVo.getSegment());
        segmentConfig.setWords(documentAddRequestVo.getWords());
        segmentConfig.setIsTrim(true);
        segmentConfig.setOverlaps(10);
        knowledgeDocumentModel.setSegmentConfig(segmentConfig);
        //默认 url地址的方式记录存储
        knowledgeDocumentModel.setDataType(StringUtils.isNotBlank(documentAddRequestVo.getDocUrl()) ? KnowledgeDataTypeEnum.URL_FILE.getCode() : KnowledgeDataTypeEnum.CUSTOM_TEXT.getCode());
        if (KnowledgeDataTypeEnum.URL_FILE.getCode().equals(knowledgeDocumentModel.getDataType())) {
            try {
                knowledgeDocumentModel.setFileSize(getFileSize(documentAddRequestVo.getDocUrl()));
            } catch (Exception e) {
                log.warn("获取文件大小失败 {}", documentAddRequestVo.getDocUrl(), e);
            }
        } else {
            knowledgeDocumentModel.setFileContent(documentAddRequestVo.getFileContent());
        }

        UserContext userContext = UserContext.builder()
                .userId(knowledgeConfig.getCreatorId())
                .userName(knowledgeConfig.getCreatorName())
                .tenantId(knowledgeConfig.getTenantId())
                .build();
        var id = this.knowledgeDocumentDomainService.customAddInfo(knowledgeDocumentModel, userContext);
        // 触发更新知识库文件预估大小值
        this.knowledgeDocumentDomainService.triggerUpdateKnowledgeFileSize(knowledgeDocumentModel.getKbId());

        return id;
    }

    /**
     * 获取远程文件大小（字节）
     */
    public static long getFileSize(String fileUrl) throws IOException, URISyntaxException {
        URL url = new URI(fileUrl).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("HEAD");  // 只获取头信息，不下载内容
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");

        int code = conn.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            throw new IOException("请求失败，HTTP状态码: " + code);
        }

        long size = conn.getContentLengthLong();
        conn.disconnect();
        return size;
    }
}
