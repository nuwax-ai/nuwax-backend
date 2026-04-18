package com.xspaceagi.knowledge.domain.docparser.impl;

import com.xspaceagi.knowledge.domain.docparser.FileParseRequest;
import com.xspaceagi.knowledge.domain.docparser.FileParseService;
import com.xspaceagi.knowledge.domain.docparser.parse.DocParser;
import com.xspaceagi.knowledge.domain.model.KnowledgeDocumentModel;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.KnowledgeException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.net.URI;

@Slf4j
@Service
public class PDFParser implements DocParser {


    @Resource
    private FileParseService fileParseService;

    @Override
    public void chunk(KnowledgeDocumentModel documentDto, UserContext userContext) {

        try (PDDocument doc = Loader.loadPDF(RandomAccessReadBuffer.createBufferFromStream(
                new URI(documentDto.getDocUrl())
                        .toURL().openStream()))) {
         PDFTextStripper stripper = new PDFTextStripper();
         String content = stripper.getText(doc);

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
