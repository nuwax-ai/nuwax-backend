package com.xspaceagi.knowledge.domain.docparser.impl;

import java.net.URL;

import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;

import com.xspaceagi.knowledge.domain.docparser.FileParseRequest;
import com.xspaceagi.knowledge.domain.docparser.FileParseService;
import com.xspaceagi.knowledge.domain.docparser.parse.DocParser;
import com.xspaceagi.knowledge.domain.model.KnowledgeDocumentModel;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.KnowledgeException;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class WordParser implements DocParser {

    @Resource
    private FileParseService fileParseService;

    @Override
    public void chunk(KnowledgeDocumentModel documentDto, UserContext userContext) {

        try (XWPFDocument document = new XWPFDocument(new URL(documentDto.getDocUrl()).openStream());
                XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            String content = extractor.getText();
            FileParseRequest fileParseRequest = FileParseRequest.builder()
                    .kbId(documentDto.getKbId())
                    .docId(documentDto.getId())
                    .spaceId(documentDto.getSpaceId())
                    .content(content)
                    .segmentConfig(documentDto.getSegmentConfig())
                    .build();
            this.fileParseService.parseRawTxt(fileParseRequest, userContext);
        } catch (Exception e) {
            log.error("Failed to parse document", e);
            throw KnowledgeException.build(BizExceptionCodeEnum.knowledgeDocumentParseFailed);
        }
    }

    @Override
    public Boolean isSupport(Integer dataType, String docUrl) {
        return false;
    }
}
