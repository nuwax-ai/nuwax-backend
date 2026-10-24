package com.xspaceagi.knowledge.domain.knowledgeimageanalysis;

import com.xspaceagi.knowledge.core.spec.utils.Constants;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 Tika 的文档解析器(通用兜底), 在解析文本的同时捕获嵌入图片。
 *
 * <p>PDF/DOCX 等具备精确位置解析的场景应优先使用 {@link ImageAwareDocumentParser};
 * 本解析器作为 Tika 通用兜底, 将提取到的图片以 Markdown 形式追加到正文之后。
 */
@Slf4j
public class TikaImageAwareParser {

    /**
     * 解析文档, 提取文本与图片。
     *
     * @param in       输入流
     * @param callback 图片上传回调, 为 null 时仅提取文本
     */
    public DocumentImageParseResult parse(InputStream in, ImageUploadCallback callback) {
        try {
            Parser parser = new AutoDetectParser();
            BodyContentHandler handler = new BodyContentHandler(Constants.TIKA_TEXT_WRITE_LIMIT);
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();

            // PDF: 提取内联图片, 但由 extractor 内部按内容去重
            PDFParserConfig pdfConfig = new PDFParserConfig();
            pdfConfig.setExtractInlineImages(true);
            pdfConfig.setExtractUniqueInlineImagesOnly(false);
            // 显式关闭页面OCR(兜底路径防护): 自定义 EmbeddedDocumentExtractor 会使 Tika 对无
            // 文本层页进入OCR分支,环境无OCR引擎时 ocrParser 为 null 直接NPE(实测2026-10-20二分验证)。
            // 注意: PDF/DOCX生产主路径走 ImageAwareDocumentParser(纯PDFBox/POI),不经过此类。
            pdfConfig.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
            context.set(PDFParserConfig.class, pdfConfig);

            TikaImageExtractingExtractor extractor =
                    new TikaImageExtractingExtractor(context, callback);
            context.set(EmbeddedDocumentExtractor.class, extractor);

            parser.parse(in, handler, metadata, context);

            String content = handler.toString();
            List<DocumentImageParseResult.ImageInfo> images = extractor.getImages();

            // 将图片以 Markdown 形式追加到正文(Tika 无法精确定位)
            if (!images.isEmpty()) {
                StringBuilder sb = new StringBuilder(content == null ? "" : content);
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
                    sb.append('\n');
                }
                for (DocumentImageParseResult.ImageInfo info : images) {
                    if (info.getUploadedUrl() != null) {
                        sb.append(MarkdownImageFormatter.format(info.getUploadedUrl()))
                                .append('\n');
                    }
                }
                content = sb.toString();
            }

            return DocumentImageParseResult.builder()
                    .content(content)
                    .totalImages(images.size())
                    .images(images)
                    .success(true)
                    .build();
        } catch (Exception e) {
            log.error("Tika 解析失败", e);
            return DocumentImageParseResult.builder()
                    .success(false)
                    .errorMessage("Tika 解析失败: " + e.getMessage())
                    .images(new ArrayList<>())
                    .build();
        }
    }
}
