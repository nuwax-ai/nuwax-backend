package com.xspaceagi.knowledge.domain.docparser.impl;

import com.xspaceagi.knowledge.core.spec.enums.KnowledgeDataTypeEnum;
import com.xspaceagi.knowledge.domain.docparser.FileParseExtService;
import com.xspaceagi.knowledge.domain.docparser.FileParseRequest;
import com.xspaceagi.knowledge.domain.docparser.FileParseService;
import com.xspaceagi.knowledge.domain.docparser.parse.DocParser;
import com.xspaceagi.knowledge.domain.model.KnowledgeDocumentModel;
import com.xspaceagi.knowledge.sdk.enums.SegmentEnum;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.KnowledgeException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Objects;

@Slf4j
@Service
public class TXTParser implements DocParser {

    @Resource
    private FileParseService fileParseService;

    @Resource
    private FileParseExtService fileParseExtService;

    @Override
    public void chunk(KnowledgeDocumentModel documentDto, UserContext userContext) {

        var dataType = documentDto.getDataType();

        var dataTypeEnum = KnowledgeDataTypeEnum.getEnumByCode(dataType);
        if (Objects.isNull(dataTypeEnum)) {
            log.error("Failed to parse document, unsupported file type [{}]", dataType);
            throw KnowledgeException.build(BizExceptionCodeEnum.knowledgeDocumentUnsupportedType);
        }
        switch (dataTypeEnum) {
            case URL_FILE -> {
                StringBuilder content = new StringBuilder();
                try (BufferedReader reader =
                             new BufferedReader(new InputStreamReader(new URL(documentDto.getDocUrl()).openStream()))) {
                    content.append(reader.lines()
                            .toList());

                    FileParseRequest fileParseRequest = FileParseRequest.builder()
                            .kbId(documentDto.getKbId())
                            .docId(documentDto.getId())
                            .spaceId(documentDto.getSpaceId())
                            .content(content.toString())
                            .segmentConfig(documentDto.getSegmentConfig())
                            .build();
                    /*
                    this.fileParseService.parseRawTxt(fileParseRequest,
                            userContext);*/
                    String docUrl = "";
                    if (documentDto != null && documentDto.getSegmentConfig() != null && documentDto.getSegmentConfig().getSegment().equals(SegmentEnum.SMART)) {
                        fileParseExtService.parseRawTxt(fileParseRequest, userContext, docUrl);
                    } else {
                        this.fileParseService.parseRawTxt(fileParseRequest,
                                userContext);
                    }
                } catch (KnowledgeException e) {
                    // 业务异常(如租户默认模型不可用)原样上抛,保留具体原因供文档处理结果展示
                    throw e;
                } catch (Exception e) {
                    log.error("Failed to parse document", e);
                    throw KnowledgeException.build(BizExceptionCodeEnum.knowledgeDocumentParseFailed);
                }
            }
            case CUSTOM_TEXT -> {
                try {

                    FileParseRequest fileParseRequest = FileParseRequest.builder()
                            .kbId(documentDto.getKbId())
                            .docId(documentDto.getId())
                            .spaceId(documentDto.getSpaceId())
                            .content(documentDto.getFileContent())
                            .segmentConfig(documentDto.getSegmentConfig())
                            .build();
                    /*
                    this.fileParseService.parseRawTxt(fileParseRequest,
                            userContext);*/
                    String docUrl = "";
                    if (documentDto != null && documentDto.getSegmentConfig() != null && documentDto.getSegmentConfig().getSegment().equals(SegmentEnum.SMART)) {
                        fileParseExtService.parseRawTxt(fileParseRequest, userContext, docUrl);
                    } else {
                        this.fileParseService.parseRawTxt(fileParseRequest,
                                userContext);
                    }
                } catch (KnowledgeException e) {
                    // 业务异常(如租户默认模型不可用)原样上抛,保留具体原因供文档处理结果展示
                    throw e;
                } catch (Exception e) {
                    log.error("Failed to parse document", e);
                    throw KnowledgeException.build(BizExceptionCodeEnum.knowledgeDocumentParseFailed);
                }
            }
            default -> {
                log.error("Failed to parse document, unsupported file type [{}]", dataTypeEnum);
                throw KnowledgeException.build(BizExceptionCodeEnum.knowledgeDocumentUnsupportedType);
            }
        }


    }

    @Override
    public Boolean isSupport(Integer dataType, String docUrl) {

        // 根据 dataType 和 docUrl 判断是否支持
        var dataTypeEnum = KnowledgeDataTypeEnum.getEnumByCode(dataType);
        return KnowledgeDataTypeEnum.CUSTOM_TEXT == dataTypeEnum;
    }
}
