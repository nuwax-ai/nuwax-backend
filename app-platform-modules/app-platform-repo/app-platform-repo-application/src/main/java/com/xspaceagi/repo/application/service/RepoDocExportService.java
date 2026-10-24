package com.xspaceagi.repo.application.service;

import com.lowagie.text.pdf.BaseFont;
import com.xspaceagi.file.application.service.FileManagementService;
import com.xspaceagi.file.domain.model.FileRecordDomain;
import com.xspaceagi.file.sdk.IFileAccessService;
import com.xspaceagi.repo.domain.model.RepoPageDomain;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.WordprocessingML.BinaryPartAbstractImage;
import org.docx4j.openpackaging.parts.WordprocessingML.MainDocumentPart;
import org.docx4j.openpackaging.parts.relationships.Namespaces;
import org.docx4j.relationships.Relationship;
import org.docx4j.wml.BooleanDefaultTrue;
import org.docx4j.wml.Br;
import org.docx4j.wml.CTBorder;
import org.docx4j.wml.CTShd;
import org.docx4j.wml.CTTblLayoutType;
import org.docx4j.wml.HpsMeasure;
import org.docx4j.wml.Jc;
import org.docx4j.wml.JcEnumeration;
import org.docx4j.wml.ObjectFactory;
import org.docx4j.wml.P;
import org.docx4j.wml.PPr;
import org.docx4j.wml.PPrBase;
import org.docx4j.wml.R;
import org.docx4j.wml.RFonts;
import org.docx4j.wml.RPr;
import org.docx4j.wml.STBorder;
import org.docx4j.wml.STTblLayoutType;
import org.docx4j.wml.Tbl;
import org.docx4j.wml.TblBorders;
import org.docx4j.wml.TblGrid;
import org.docx4j.wml.TblGridCol;
import org.docx4j.wml.TblPr;
import org.docx4j.wml.TblWidth;
import org.docx4j.wml.Tc;
import org.docx4j.wml.TcMar;
import org.docx4j.wml.TcPr;
import org.docx4j.wml.TcPrInner;
import org.docx4j.wml.Text;
import org.docx4j.wml.Tr;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.xhtmlrenderer.pdf.ITextRenderer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档导出转换服务（需求3：docx/pdf 后端转换，直接下载）。
 * <p>
 * 流程：html_snapshot → jsoup 解析 → 平台图片（/api/f/{key}）转 base64 data URI →
 * 目标格式：
 * <ul>
 *   <li>pdf：XHTML + flying-saucer（OpenPDF），内置文泉驿微米黑保证中文不乱码；</li>
 *   <li>docx：docx4j 原生对象构建（遍历 Tiptap 块/行内结构）。不用 docx4j-ImportXHTML——
 *       其传递依赖 openhtmltopdf-pdfbox 1.0.10 仅兼容 pdfbox 2.x，与平台知识库使用的
 *       pdfbox 3.0.3 冲突（NoSuchFieldError: COURIER_BOLD_OBLIQUE）。</li>
 * </ul>
 * 流程图（.flow-diagram）在预处理阶段经 {@link RepoFlowImageService} 自绘 SVG → PNG 内嵌，
 * docx/pdf 两条链路统一生效；渲染失败退化为「[流程图]」占位文本。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepoDocExportService {

    /**
     * 内置中文字体（文泉驿微米黑 TTC，首段索引 0）
     */
    private static final String FONT_CLASSPATH = "fonts/wqy-microhei.ttc";

    private static final String FONT_FAMILY = "WenQuanYi Micro Hei";

    /**
     * OpenPDF 注册字体需要磁盘路径，TTC 用「路径,0」语法；懒加载一次
     */
    private static volatile String fontFilePath;

    private static final Object FONT_LOCK = new Object();

    private static final Map<String, String> EXT_MIME = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "gif", "image/gif",
            "webp", "image/webp",
            "bmp", "image/bmp",
            "svg", "image/svg+xml");

    /**
     * docx 图片最大宽度：6 英寸（EMU，1 英寸 = 914400）
     */
    private static final long DOCX_MAX_IMAGE_CX = 6L * 914400L;

    /**
     * docx 表格总宽度（twips，A4 内容区约 9026）：固定布局下均分给各列
     */
    private static final int TABLE_TOTAL_WIDTH_TWIPS = 9000;

    /**
     * 表格最小列宽（twips，约 0.5cm）：colgroup 比例分配时防窄列塌成 0
     */
    private static final int MIN_COL_WIDTH_TWIPS = 280;

    /**
     * docx 正文默认字号：12pt = 24 半磅
     */
    private static final int BODY_SIZE_HALF_PT = 24;

    /**
     * 标题字号（半磅）：h1..h6
     */
    private static final int[] HEADING_SIZES = {44, 38, 32, 28, 26, 24};

    /**
     * 文档导出标题字号（半磅）：24pt = 48 半磅（需求5：导出顶部补标题）
     */
    private static final int TITLE_SIZE_HALF_PT = 48;

    /**
     * 图片显式宽度（CSS px）解析：style 里 width:Npx，排除 max-width/min-width 等
     */
    private static final Pattern IMG_WIDTH_STYLE =
            Pattern.compile("(?<![\\w-])width\\s*:\\s*(\\d+(?:\\.\\d+)?)px", Pattern.CASE_INSENSITIVE);

    /**
     * rgb(r,g,b) / rgba(r,g,b,a) 解析
     */
    private static final Pattern CSS_RGB =
            Pattern.compile("rgba?\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*(?:,\\s*([\\d.]+)\\s*)?\\)");

    /**
     * @用户 mention 徽章配色（与编辑态 .mention 一致：蓝字 + 浅蓝底）
     */
    private static final String MENTION_COLOR = "3370FF";

    private static final String MENTION_SHADE = "E8F0FF";

    private static final String QUOTE_COLOR = "8A8F8D";

    private static final String BORDER_COLOR = "DEE0E3";

    private static final String CODE_SHADE = "F5F6F7";

    /**
     * 代码块单元格内边距（twips）：左右 12pt（240）、上下 6pt（120），
     * 让代码文字不贴灰底边缘（导出的 Word 代码块四周留白）。
     */
    private static final int CODE_PAD_X_TWIPS = 240;

    private static final int CODE_PAD_Y_TWIPS = 120;

    private static final ObjectFactory WML = new ObjectFactory();

    private final FileManagementService fileManagementService;

    private final RepoFlowImageService repoFlowImageService;

    private final IFileAccessService fileAccessService;

    /**
     * docx 图片关系序号（cNvPr id/docPrId 全文档唯一）
     */
    private final AtomicInteger imageSeq = new AtomicInteger(0);

    /**
     * 行内样式累加器（不可变传递，嵌套标签逐层叠加）
     */
    private record InlineStyle(boolean bold, boolean italic, boolean strike, boolean underline,
                               boolean code, boolean quote, Integer sizeHalfPt,
                               String color, String shade) {
        InlineStyle withBold() {
            return new InlineStyle(true, italic, strike, underline, code, quote, sizeHalfPt, color, shade);
        }

        InlineStyle withItalic() {
            return new InlineStyle(bold, true, strike, underline, code, quote, sizeHalfPt, color, shade);
        }

        InlineStyle withStrike() {
            return new InlineStyle(bold, italic, true, underline, code, quote, sizeHalfPt, color, shade);
        }

        InlineStyle withUnderline() {
            return new InlineStyle(bold, italic, strike, true, code, quote, sizeHalfPt, color, shade);
        }

        InlineStyle withCode() {
            return new InlineStyle(bold, italic, strike, underline, true, quote, sizeHalfPt, color, shade);
        }

        InlineStyle withQuote() {
            return new InlineStyle(bold, italic, strike, underline, code, true, sizeHalfPt, color, shade);
        }

        InlineStyle withSize(int size) {
            return new InlineStyle(bold, italic, strike, underline, code, quote, size, color, shade);
        }

        /** 显式文字色（hex 无 #），null 保持既有 */
        InlineStyle withColor(String c) {
            return c == null ? this : new InlineStyle(bold, italic, strike, underline, code, quote, sizeHalfPt, c, shade);
        }

        /** 显式背景高亮（hex 无 #），null 保持既有 */
        InlineStyle withShade(String s) {
            return s == null ? this : new InlineStyle(bold, italic, strike, underline, code, quote, sizeHalfPt, color, s);
        }
    }

    /**
     * 导出为 docx 字节
     */
    public byte[] exportDocx(RepoPageDomain page) {
        try {
            Document doc = preprocess(page.getHtmlSnapshot());
            WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
            MainDocumentPart main = pkg.getMainDocumentPart();
            Element body = doc.body();
            if (body != null) {
                // 需求5：顶部补标题（正文首块已是同文字标题时跳过，避免重复）
                if (shouldInjectTitle(body, page.getTitle())) {
                    P titleP = newParagraph(0, false);
                    // 导出标题居中（docx/pdf 一致）
                    Jc jc = WML.createJc();
                    jc.setVal(JcEnumeration.CENTER);
                    ensurePPr(titleP).setJc(jc);
                    addTextRun(titleP, page.getTitle().trim(),
                            new InlineStyle(true, false, false, false, false, false, TITLE_SIZE_HALF_PT, null, null), false);
                    main.addObject(titleP);
                }
                for (Element block : body.children()) {
                    buildBlock(pkg, block, 0, new InlineStyle(false, false, false, false, false, false, BODY_SIZE_HALF_PT, null, null), false)
                            .forEach(main::addObject);
                }
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            pkg.save(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("export docx failed, pageId=" + page.getId(), e);
        }
    }

    /**
     * 导出为 pdf 字节
     */
    public byte[] exportPdf(RepoPageDomain page) {
        try {
            String xhtml = toXhtml(preprocess(page.getHtmlSnapshot()), page.getTitle());
            ITextRenderer renderer = new ITextRenderer();
            String fontPath = ensureFontFile();
            renderer.getFontResolver().addFont(fontPath + ",0", BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            renderer.setDocumentFromString(xhtml);
            renderer.layout();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            renderer.createPDF(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("export pdf failed, pageId=" + page.getId(), e);
        }
    }

    // ============================ 预处理 ============================

    /**
     * 解析快照并把平台图片（/api/f/{key}）内嵌为 base64 data URI；
     * 无效/blob 图片直接移除。返回 jsoup Document（body 内为正文块）。
     */
    private Document preprocess(String htmlSnapshot) {
        Document doc = Jsoup.parse(htmlSnapshot == null ? "" : htmlSnapshot);
        // 流程图（需求3）：后端自绘 PNG 内嵌，docx 走 addImageRun、pdf 走 XHTML base64 img；
        // 渲染失败（空图/解析异常）退化为占位段落
        for (Element flow : doc.select("div.flow-diagram")) {
            byte[] png = repoFlowImageService.renderFlowToPng(flow.attr("data-flow"));
            if (png != null && png.length > 0) {
                Element img = new Element("img");
                img.attr("src", "data:image/png;base64," + Base64.getEncoder().encodeToString(png));
                flow.replaceWith(img);
            } else {
                Element p = new Element("p");
                p.text("[流程图]");
                flow.replaceWith(p);
            }
        }
        // 思维导图（mind-elixir）：后端自绘 PNG 内嵌，docx 走 addImageRun、pdf 走 XHTML base64 img；
        // 渲染失败（空图/解析异常）退化为占位段落
        for (Element mind : doc.select("div.mind-map")) {
            byte[] png = repoFlowImageService.renderMindmapToPng(mind.attr("data-mindmap"));
            if (png != null && png.length > 0) {
                Element img = new Element("img");
                img.attr("src", "data:image/png;base64," + Base64.getEncoder().encodeToString(png));
                mind.replaceWith(img);
            } else {
                Element p = new Element("p");
                p.text("[思维导图]");
                mind.replaceWith(p);
            }
        }
        // 数学公式（需求3 Batch3 增补）：节点属性里带着前端算好的 MathJax SVG（data-math-svg，
        // base64），这里解码 → Batik 栅格化成 PNG → 内嵌，docx 走 addImageRun、pdf 走 base64 img。
        // 为什么必须转成图片而不能留 SVG/文本：docx 侧不认识的 inline 标签会落进 addInlineNode 的
        // default 分支，被当成一坨散乱文本；pdf 侧（flying-saucer）没有公式字体，直接排版必崩。
        // 栅格化失败则**保留节点原样**（元素文本就是 TeX 源码）——导出里降级成可读文本而非空白。
        for (Element math : doc.select("[data-math-svg]")) {
            // 解码一次，栅格化与取尺寸共用（SVG 有 3-15KB，避免重复 atob）
            String svg = decodeMathSvg(math.attr("data-math-svg"));
            byte[] png = repoFlowImageService.rasterizeMath(svg);
            if (png == null || png.length == 0) {
                continue;
            }
            Element img = new Element("img");
            img.attr("src", "data:image/png;base64," + Base64.getEncoder().encodeToString(png));
            // data-math 标记：addInlineNode 的 img 分支据此豁免「表格单元格内跳过图片」，
            // 否则表格里的公式会整个消失（连降级文本都不剩，因为文本已被下面清空）
            img.attr("data-math", "1");
            // 逻辑宽度必须显式写死：PNG 是按 SCALE=2 超采样出来的（像素尺寸是逻辑尺寸的 2 倍），
            // 不写宽度的话 pdf flying-saucer 会按 PNG 的自然像素排版 → 公式变成 2 倍大。
            // **只写 width 不写 height**：排版侧都有 `img{max-width:100%}`，宽度被压窄时若高度写死
            // 会把公式压扁；只给宽度则 docx（addImageRun 按固有比例算 cy）与 pdf（按固有比例算高度）
            // 都会等比缩放。
            Long widthPx = mathLogicalWidthPx(svg);
            if (widthPx != null) {
                // 必须是整数：parseImageWidthPx 的属性分支用 Long.parseLong，带小数点会抛异常
                img.attr("width", String.valueOf(widthPx));
            }
            boolean block = "block-math".equals(math.attr("data-type"));
            if (!block) {
                // 行内公式的基线对齐：MathJax 把「下沉多少像素」放在 SVG 根标签的
                // style="vertical-align: -1.49px" 里（= 公式深度），换成 <img> 后这个信息就丢了
                // ——不补的话公式会整体浮高几个像素，与周围文字对不齐。pdf 认这个属性；
                // docx 的 addImageRun 读的是 style 里的 width，不受影响。
                String va = parseSvgStyleValue(svg, "vertical-align");
                if (va != null) {
                    img.attr("style", "vertical-align:" + va + ";");
                }
            }
            // 保留外层元素（span/div）而不是整个替换成 img：docx buildBlock 的 switch 只认
            // div/section/article，块级公式若换成裸 img 会掉出分支；且块级需要居中对齐
            math.empty();
            math.appendChild(img);
            if (block) {
                math.attr("style", "text-align: center;");
            }
        }
        // 视频/音频（多媒体节点）：Word/PDF 无法内嵌播放。视频有封面帧（data-poster-url，前端插入时
        // 抽首帧上传）则内嵌该画面 + 下方「[视频] 文件名」标注，让 Word 里能看到视频画面而非一行文字；
        // 无封面（老数据/抽帧失败）回退「[视频] 文件名」占位；音频无画面，保持「[音频] 文件名」占位。
        // 否则 docx4j/flying-saucer 不认识 video/audio 标签，会把其 fallback 文本「您的浏览器不支持…播放」
        // 当正文导出，既无意义又漏掉文件名。
        for (Element media : doc.select("div.media-video, div.media-audio")) {
            boolean isVideo = media.className().contains("media-video");
            String name = media.attr("data-name");
            String label = (isVideo ? "[视频] " : "[音频] ") + (name == null || name.isBlank() ? "" : name);
            String posterUrl = media.attr("data-poster-url");
            if (isVideo && posterUrl != null && !posterUrl.isBlank()) {
                // 封面 img 交由下方 img 循环 inlineImage 下载转 data URI（docx addImageRun 内嵌、pdf base64）；
                // jsoup replaceWith 只收单节点，用 replaceWith(img) + after(cap) 排成「画面 + 标注」两块
                Element img = new Element("img");
                img.attr("src", posterUrl);
                Element cap = new Element("p");
                cap.text(label);
                media.replaceWith(img);
                img.after(cap);
            } else {
                Element p = new Element("p");
                p.text(label);
                media.replaceWith(p);
            }
        }
        // 附件（attachment 节点）：导出为可点击下载超链接。附件卡片是行内 span.attachment-card（含
        // badge/name/size 子元素），历史数据也有 div.attachment-card 形态。此处统一替换成 <a href=下载地址>：
        // PDF 走 flying-saucer 的 <a href> 直接成为链接；docx 在 addInlineNode 的 case "a" 读 href 建 external
        // hyperlink。下载地址补 ak（免登录直访）：本地存储回「?ak=」、云存储回 24h 预签名。补不到地址则降级纯文本。
        for (Element att : doc.select("span.attachment-card, div.attachment-card")) {
            Element nameEl = att.selectFirst(".attachment-card__name");
            String name = nameEl != null && !nameEl.text().isBlank() ? nameEl.text() : "附件";
            String href = resolveAttachmentUrl(att);
            Element a = new Element("a");
            a.attr("href", href != null ? href : "#");
            a.text("📎 " + name);
            if (href == null) {
                // 无可用下载地址：换成纯文本，去掉无效的 # 链接
                a.removeAttr("href");
            }
            att.replaceWith(a);
        }
        for (Element img : doc.select("img[src]")) {
            // 公式图（上面刚生成的 data URI）不再走平台图片内嵌分支：extractFileKey 只认
            // /api/f/，data URI 本不会命中，但 base64 理论上可能恰好含该子串，命中后会被
            // 当成「下载失败」直接 remove —— 公式就凭空消失了。宽度也已显式写好，无需再同步。
            if (img.hasAttr("data-math")) {
                continue;
            }
            String src = img.attr("src");
            String fileKey = extractFileKey(src);
            if (fileKey != null) {
                if (!inlineImage(img, fileKey)) {
                    img.remove();
                    continue;
                }
            } else if (src.isBlank() || src.startsWith("blob:")) {
                img.remove();
                continue;
            }
            // 需求5：显式宽度同步成 width 属性（CSS px）。pdf flying-saucer 读 img width 属性，
            // docx addImageRun 读 style/width 转 EMU——两条链路按文档里设置的宽度导出。
            Long px = parseImageWidthPx(img);
            if (px != null && px > 0 && img.attr("width").isBlank()) {
                img.attr("width", String.valueOf(px));
            }
        }
        return doc;
    }

    /**
     * 解出公式节点属性里的 SVG（{@code data-math-svg}，base64）。
     *
     * 前端为什么要 base64：SVG 里既有 {@code "} 又有 {@code <}，直接写进 HTML 属性会被 jsoup
     * 在第一个双引号处截断，导出拿到半截 SVG → Batik 解析失败 → 公式消失。
     *
     * 解不开（脏数据 / 手工改过快照）返回空串，交由 {@code rasterizeMath} 的空值分支走降级。
     */
    private static String decodeMathSvg(String base64) {
        if (base64 == null || base64.isBlank()) {
            return "";
        }
        try {
            return new String(Base64.getDecoder().decode(base64.trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            log.warn("decode math svg failed: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 取公式的逻辑显示宽度（CSS px，整数）。
     *
     * 前端生成时已把 MathJax 的 ex/em 全部换算成 px（见 nuwax-repo-web/src/lib/mathjax-render.ts
     * 的 normalizeUnits：1em=16px、1ex=8px），所以这里直接读 SVG 根标签的 width 即为期望的显示
     * 宽度；而 Batik 是按 SCALE=2 超采样成 PNG 的（像素是逻辑值的 2 倍），必须显式回落，否则
     * pdf 按 PNG 自然像素排版会把公式放大一倍。
     *
     * 读不到（属性缺失/单位异常）返回 null，调用方退回「不写宽度」的旧行为。
     */
    private static Long mathLogicalWidthPx(String svg) {
        Double w = parseSvgLengthPx(svg, "width");
        if (w == null || w <= 0) {
            return null;
        }
        return Math.round(w);
    }

    /** 读 SVG 根标签 style 里某个 CSS 属性的原始值（MathJax 只往里放 vertical-align），取不到返回 null */
    private static String parseSvgStyleValue(String svg, String prop) {
        if (svg == null || svg.isEmpty()) {
            return null;
        }
        int idx = svg.indexOf("style=\"");
        if (idx < 0) {
            return null;
        }
        int start = idx + "style=\"".length();
        int end = svg.indexOf('"', start);
        if (end < 0) {
            return null;
        }
        String style = svg.substring(start, end);
        int p = style.indexOf(prop + ":");
        if (p < 0) {
            return null;
        }
        int from = p + prop.length() + 1;
        int to = style.indexOf(';', from);
        String value = (to < 0 ? style.substring(from) : style.substring(from, to)).trim();
        return value.isEmpty() ? null : value;
    }

    /** 读 SVG 根标签某个长度属性并规整成 px 数值（容忍 {@code px} 后缀与裸数字） */
    private static Double parseSvgLengthPx(String svg, String name) {
        if (svg == null || svg.isEmpty()) {
            return null;
        }
        int idx = svg.indexOf(name + "=\"");
        if (idx < 0) {
            return null;
        }
        int start = idx + name.length() + 2;
        int end = svg.indexOf('"', start);
        if (end < 0) {
            return null;
        }
        StringBuilder digits = new StringBuilder();
        for (int i = start; i < end; i++) {
            char c = svg.charAt(i);
            boolean leadingSign = (c == '-' || c == '+') && digits.length() == 0;
            if (Character.isDigit(c) || c == '.' || leadingSign) {
                digits.append(c);
            } else {
                break;
            }
        }
        if (digits.length() == 0) {
            return null;
        }
        try {
            return Double.parseDouble(digits.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
    /**
     * pdf 用：jsoup Document → XHTML 字符串（含内联排版样式）
     */
    private String toXhtml(Document doc, String title) {
        doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml)
                .escapeMode(org.jsoup.nodes.Entities.EscapeMode.xhtml);
        String headCss = collectStyles(doc.head());
        String body = doc.body() == null ? "" : doc.body().html();
        String titleHtml = buildTitleHtml(doc.body(), title);
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head>"
                + "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\"/>"
                + "<style>"
                + "body{font-family:'" + FONT_FAMILY + "',sans-serif;font-size:12px;line-height:1.6;color:#1f2329;}"
                + "img{max-width:100%;}"
                + "a{color:#0563C1;text-decoration:underline;}"
                + "table{border-collapse:collapse;}"
                + "td,th{border:1px solid #dee0e3;padding:4px 8px;}"
                + "pre{background:#f5f6f7;padding:8px;white-space:pre-wrap;}"
                + ".doc-export-title{font-size:24px;font-weight:bold;margin:0 0 16px;text-align:center;}"
                // 代码语法高亮：github 亮色主题 token 色（与编辑器内/版本预览一致）
                + ".hljs-keyword,.hljs-doctag,.hljs-template-tag,.hljs-template-variable,.hljs-type{color:#d73a49;}"
                + ".hljs-title,.hljs-title.class_,.hljs-title.function_{color:#6f42c1;}"
                + ".hljs-string,.hljs-regexp{color:#032f62;}"
                + ".hljs-number,.hljs-literal,.hljs-attr,.hljs-attribute,.hljs-operator,.hljs-variable,.hljs-meta,.hljs-selector-attr,.hljs-selector-class,.hljs-selector-id{color:#005cc5;}"
                + ".hljs-built_in,.hljs-symbol{color:#e36209;}"
                + ".hljs-comment,.hljs-code,.hljs-formula{color:#6a737d;}"
                + ".hljs-name,.hljs-quote,.hljs-selector-tag,.hljs-selector-pseudo{color:#22863a;}"
                + ".hljs-addition{color:#22863a;}"
                + ".hljs-deletion{color:#b31d28;}"
                + ".hljs-section{color:#005cc5;font-weight:bold;}"
                + ".hljs-strong{font-weight:bold;}"
                + ".hljs-emphasis{font-style:italic;}"
                + "</style>"
                // 原始 CSS 用 CDATA 包裹：flying-saucer 按 XML 解析整份文档，若 CSS 里含裸 & 或 <
                // 会触发「实体名称必须紧跟在 & 后面」的 SAX 错误；CDATA 内内容不被当作 XML 实体解析。
                + "<style><![CDATA[" + headCss + "]]></style></head><body>" + titleHtml + body + "</body></html>";
    }

    /**
     * pdf 顶部标题片段（需求5）：标题非空且正文首块非「同文字标题」时生成 <h1>，否则空串。
     * 标题做 XML/HTML 转义，防止标题里的 &、< 破坏 XHTML。
     */
    private String buildTitleHtml(Element body, String title) {
        if (!shouldInjectTitle(body, title)) {
            return "";
        }
        return "<h1 class=\"doc-export-title\">" + org.jsoup.nodes.Entities.escape(title.trim()) + "</h1>";
    }

    /**
     * 是否在正文顶部补标题（需求5）：标题非空，且正文首块不是「同文字的 h1..h6」。
     * docx/pdf 共用，避免「正文第一段本来就是标题」时重复输出。
     */
    private boolean shouldInjectTitle(Element body, String title) {
        if (title == null || title.isBlank()) {
            return false;
        }
        Element first = body == null || body.children().isEmpty() ? null : body.children().first();
        if (first == null) {
            return true;
        }
        String tag = first.tagName().toLowerCase(Locale.ROOT);
        if (!(tag.equals("h1") || tag.equals("h2") || tag.equals("h3")
                || tag.equals("h4") || tag.equals("h5") || tag.equals("h6"))) {
            return true;
        }
        return !title.trim().equals(first.text().trim());
    }

    /**
     * 收集文档 <head> 里的原始 <style> 文本（导入的 html 自带 CSS），与上面的兜底样式合并输出，
     * 让导入 HTML 的配色/字体/背景等在 PDF 里尽量还原。
     * flying-saucer 仅识别 CSS2.1 子集，flex/grid/@media 等现代布局无法完全还原（渲染器上限）；
     * 不解析 <link> 外链样式（远程资源不可靠，跨域/失效风险）。
     */
    private static String collectStyles(Element head) {
        if (head == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Element style : head.select("style")) {
            sb.append(style.data());
        }
        return sb.toString();
    }

    /**
     * 图片内嵌为 base64 data URI（docx/pdf 共用）。失败返回 false（调用方移除该图）。
     */
    private boolean inlineImage(Element img, String fileKey) {
        try (InputStream in = fileManagementService.downloadFile(fileKey)) {
            if (in == null) {
                return false;
            }
            byte[] bytes = in.readAllBytes();
            if (bytes.length == 0) {
                return false;
            }
            String mime = EXT_MIME.getOrDefault(guessExt(fileKey), "image/png");
            img.attr("src", "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes));
            return true;
        } catch (Exception e) {
            log.warn("export inline image failed, fileKey={}, error={}", fileKey, e.getMessage());
            return false;
        }
    }

    /**
     * 解析附件下载地址（补 ak 的免登录直访地址）。
     * 优先用 data-key 走 fileManagementService.getFileUrl 拿绝对 /api/f/{key} 地址（跨存储策略统一），
     * 回落 data-url（前端插入时已存平台地址）；再经 fileAccessService.getFileUrlWithAk 补 ak：
     * 本地存储回「?ak=」、云存储回 24h 预签名。任何一步失败回落到原始地址（能点但可能需登录），
     * 完全拿不到则返回 null（调用方降级纯文本）。
     */
    private String resolveAttachmentUrl(Element att) {
        String key = att.attr("data-key");
        String url = att.attr("data-url");
        String target = null;
        if (key != null && !key.isBlank()) {
            try {
                target = fileManagementService.getFileUrl(key);
            } catch (Exception e) {
                log.debug("resolveAttachmentUrl getFileUrl failed, key={}", key);
            }
        }
        if (target == null || target.isBlank()) {
            target = url;
        }
        if (target == null || target.isBlank()) {
            return null;
        }
        try {
            return fileAccessService.getFileUrlWithAk(target, true);
        } catch (Exception e) {
            log.warn("resolveAttachmentUrl getFileUrlWithAk failed, target={}, error={}", target, e.getMessage());
            return target;
        }
    }

    /**
     * 从 /api/f/{key} 形式的 src 提取文件 key。
     * fileKey 为多级路径（如 local/default/20260815/uuid.png），须完整保留到查询串/锚点前
     */
    private String extractFileKey(String src) {
        if (src == null) {
            return null;
        }
        int idx = src.indexOf("/api/f/");
        if (idx < 0) {
            return null;
        }
        String key = src.substring(idx + "/api/f/".length());
        int query = key.indexOf('?');
        if (query >= 0) {
            key = key.substring(0, query);
        }
        int hash = key.indexOf('#');
        if (hash >= 0) {
            key = key.substring(0, hash);
        }
        return key.isBlank() ? null : key;
    }

    /**
     * 猜测图片扩展名：优先文件记录的原始文件名，回落 key 后缀，兜底 png
     */
    private String guessExt(String fileKey) {
        try {
            FileRecordDomain record = fileManagementService.getFileByKey(fileKey);
            if (record != null && record.getFileName() != null) {
                int dot = record.getFileName().lastIndexOf('.');
                if (dot >= 0 && dot < record.getFileName().length() - 1) {
                    String ext = record.getFileName().substring(dot + 1).toLowerCase(Locale.ROOT);
                    if (EXT_MIME.containsKey(ext)) {
                        return ext;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("guessExt getFileByKey failed, fileKey={}", fileKey);
        }
        int dot = fileKey.lastIndexOf('.');
        if (dot >= 0 && dot < fileKey.length() - 1) {
            String ext = fileKey.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (EXT_MIME.containsKey(ext)) {
                return ext;
            }
        }
        return "png";
    }

    // ============================ docx 原生构建 ============================

    /**
     * 块级分发：返回该块生成的 JAXB 对象列表（P / Tbl），由调用方挂到文档或单元格。
     *
     * @param indent 缩进层级（嵌套列表）
     * @param style  行内样式累加状态
     * @param inCell 表格单元格内（图片跳过，避免单元格内嵌大图）
     */
    private List<Object> buildBlock(WordprocessingMLPackage pkg, Element el, int indent, InlineStyle style, boolean inCell) {
        List<Object> out = new java.util.ArrayList<>();
        String tag = el.tagName().toLowerCase(Locale.ROOT);
        switch (tag) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                int level = tag.charAt(1) - '1';
                P p = newParagraph(indent, false);
                addInlines(pkg, p, el, style.withBold().withSize(HEADING_SIZES[level]), inCell);
                out.add(p);
            }
            case "p" -> {
                P p = newParagraph(indent, style.quote());
                addInlines(pkg, p, el, style, inCell);
                out.add(p);
            }
            case "ul", "ol" -> buildList(pkg, el, tag.equals("ol"), indent, style, inCell, out);
            case "blockquote" -> {
                InlineStyle quoted = style.withQuote();
                for (Element child : el.children()) {
                    out.addAll(buildBlock(pkg, child, indent, quoted, inCell));
                }
            }
            case "pre" -> out.addAll(buildCodeBlock(el, indent, style));
            case "table" -> {
                if (!inCell) {
                    out.add(buildTable(pkg, el, style));
                } else {
                    // 嵌套表格（单元格内）降级为文本行，单元格以「|」分隔
                    for (Element tr : el.select("tr")) {
                        StringBuilder sb = new StringBuilder();
                        for (Element cell : tr.children()) {
                            String ct = cell.tagName().toLowerCase(Locale.ROOT);
                            if (!ct.equals("td") && !ct.equals("th")) {
                                continue;
                            }
                            if (sb.length() > 0) {
                                sb.append(" | ");
                            }
                            sb.append(cell.text());
                        }
                        P p = newParagraph(indent, false);
                        addTextRun(p, sb.toString(), style, false);
                        out.add(p);
                    }
                }
            }
            case "hr" -> {
                P p = newParagraph(0, false);
                PPr ppr = ensurePPr(p);
                PPrBase.PBdr bdr = WML.createPPrBasePBdr();
                bdr.setBottom(border(STBorder.SINGLE, "999999"));
                ppr.setPBdr(bdr);
                out.add(p);
            }
            case "img" -> {
                if (!inCell) {
                    P p = newParagraph(0, false);
                    addImageRun(pkg, p, el);
                    out.add(p);
                }
            }
            case "div", "section", "article" -> {
                // 流程图已在 preprocess 替换为内嵌 PNG img；此处仅处理附件卡与普通容器
                if (el.className().contains("attachment-card")) {
                    P p = newParagraph(indent, false);
                    String name = el.selectFirst(".attachment-card__name") != null
                            ? el.selectFirst(".attachment-card__name").text() : "附件";
                    addTextRun(p, "📎 " + name, style, false);
                    out.add(p);
                } else {
                    // 容器：先收直属行内内容，再递归块级子元素
                    List<Node> inlineNodes = el.childNodes().stream()
                            .filter(n -> !(n instanceof Element e) || isInlineTag(e.tagName()))
                            .toList();
                    boolean hasInlineText = inlineNodes.stream()
                            .anyMatch(n -> n instanceof TextNode tn ? !tn.isBlank() : true);
                    if (hasInlineText) {
                        P p = newParagraph(indent, style.quote());
                        for (Node n : inlineNodes) {
                            addInlineNode(pkg, p, n, style, inCell);
                        }
                        // 块级公式（preprocess 已把节点换成内嵌 PNG）：pdf 靠 style 里的
                        // text-align:center 居中，docx 得显式给段落加 Jc，否则两边观感不一致
                        if (el.hasClass("math-block")) {
                            Jc jc = WML.createJc();
                            jc.setVal(JcEnumeration.CENTER);
                            ensurePPr(p).setJc(jc);
                        }
                        out.add(p);
                    }
                    for (Element child : el.children()) {
                        if (!isInlineTag(child.tagName())) {
                            out.addAll(buildBlock(pkg, child, indent, style, inCell));
                        }
                    }
                }
            }
            default -> {
                // 其他块级（figure 等）：按行内容兜底
                P p = newParagraph(indent, style.quote());
                addInlines(pkg, p, el, style, inCell);
                out.add(p);
            }
        }
        return out;
    }

    /**
     * 代码块 → 带语法高亮 + 内边距的单格表格。
     * 快照里 code 块经前端 highlightCodeBlocks 处理为
     * {@code <code><span class="hljs-*">…</span>…</code>}；此处逐 span 着色输出 run，
     * 颜色取自 github 亮色主题（与编辑器内/版本预览一致）。存量纯文本 code 块
     * （无 hljs span）回退为每行一个等宽 run。所有代码行包进一个 1×1 表格单元格，
     * 单元格 tcMar 提供四周内边距、shd 提供灰底，避免文字贴代码块边缘。
     */
    private List<Object> buildCodeBlock(Element pre, int indent, InlineStyle style) {
        List<P> paragraphs = buildCodeParagraphs(pre, style);
        if (paragraphs.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        List<Object> out = new java.util.ArrayList<>();
        out.add(buildCodeCellTable(paragraphs, indent));
        return out;
    }

    /**
     * 代码块 → 代码行段落序列（每行一个段落，等宽字体 + token 着色）。
     * 底纹与内边距由外层代码块单元格统一承担，此处不再逐段 shadeParagraph。
     */
    private List<P> buildCodeParagraphs(Element pre, InlineStyle style) {
        List<P> out = new java.util.ArrayList<>();
        Element codeEl = pre.selectFirst("code");
        boolean hasHljs = codeEl != null && !codeEl.select("span[class*=hljs-]").isEmpty();
        if (!hasHljs) {
            String text = pre.text();
            String[] lines = text.split("\n", -1);
            for (String line : lines) {
                P p = newParagraph(0, false);
                R r = WML.createR();
                r.setRPr(buildRunPr(style.withCode()));
                Text t = WML.createText();
                t.setSpace("preserve");
                t.setValue(line.isEmpty() ? " " : line);
                r.getContent().add(t);
                p.getContent().add(r);
                out.add(p);
            }
            return out;
        }
        // 高亮形态：扁平化 span → (text, color, bold, italic) 片段，再按 \n 切行
        List<CodeFragment> frags = new java.util.ArrayList<>();
        collectCodeFragments(codeEl, null, false, false, frags);
        List<List<CodeFragment>> lines = new java.util.ArrayList<>();
        List<CodeFragment> current = new java.util.ArrayList<>();
        for (CodeFragment f : frags) {
            String text = f.text();
            int start = 0;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    if (i > start) {
                        current.add(new CodeFragment(text.substring(start, i), f.color(), f.bold(), f.italic()));
                    }
                    lines.add(current);
                    current = new java.util.ArrayList<>();
                    start = i + 1;
                }
            }
            if (start < text.length()) {
                current.add(new CodeFragment(text.substring(start), f.color(), f.bold(), f.italic()));
            }
        }
        lines.add(current);
        for (List<CodeFragment> line : lines) {
            P p = newParagraph(0, false);
            if (line.isEmpty()) {
                R r = WML.createR();
                r.setRPr(buildRunPr(style.withCode(), false, null, false, false));
                Text t = WML.createText();
                t.setSpace("preserve");
                t.setValue(" ");
                r.getContent().add(t);
                p.getContent().add(r);
            } else {
                for (CodeFragment f : line) {
                    addCodeRun(p, f, style);
                }
            }
            out.add(p);
        }
        return out;
    }

    /**
     * 代码块单元格：1×1 表格，tcMar 提供四周内边距（文字不贴灰底边缘）、
     * shd 提供灰底；不设表格边框，tblInd 保留列表嵌套时的左缩进。
     */
    private Tbl buildCodeCellTable(List<P> paragraphs, int indent) {
        Tbl tbl = WML.createTbl();
        TblPr tblPr = WML.createTblPr();
        TblWidth tblW = WML.createTblWidth();
        tblW.setType(TblWidth.TYPE_DXA);
        tblW.setW(BigInteger.valueOf(TABLE_TOTAL_WIDTH_TWIPS));
        tblPr.setTblW(tblW);
        CTTblLayoutType layout = WML.createCTTblLayoutType();
        layout.setType(STTblLayoutType.FIXED);
        tblPr.setTblLayout(layout);
        if (indent > 0) {
            TblWidth tblInd = WML.createTblWidth();
            tblInd.setType(TblWidth.TYPE_DXA);
            tblInd.setW(BigInteger.valueOf(indent * 480L));
            tblPr.setTblInd(tblInd);
        }
        tbl.setTblPr(tblPr);
        TblGrid grid = WML.createTblGrid();
        TblGridCol col = WML.createTblGridCol();
        col.setW(BigInteger.valueOf(TABLE_TOTAL_WIDTH_TWIPS));
        grid.getGridCol().add(col);
        tbl.setTblGrid(grid);

        Tr row = WML.createTr();
        Tc tc = WML.createTc();
        TcPr tcPr = WML.createTcPr();
        TblWidth tcW = WML.createTblWidth();
        tcW.setType(TblWidth.TYPE_DXA);
        tcW.setW(BigInteger.valueOf(TABLE_TOTAL_WIDTH_TWIPS));
        tcPr.setTcW(tcW);
        TcMar tcMar = WML.createTcMar();
        tcMar.setTop(tblWidth(CODE_PAD_Y_TWIPS));
        tcMar.setBottom(tblWidth(CODE_PAD_Y_TWIPS));
        tcMar.setLeft(tblWidth(CODE_PAD_X_TWIPS));
        tcMar.setRight(tblWidth(CODE_PAD_X_TWIPS));
        tcPr.setTcMar(tcMar);
        CTShd shd = WML.createCTShd();
        shd.setFill(CODE_SHADE);
        tcPr.setShd(shd);
        tc.setTcPr(tcPr);
        for (P p : paragraphs) {
            tc.getContent().add(p);
        }
        row.getContent().add(tc);
        tbl.getContent().add(row);
        return tbl;
    }

    /** dxa 单位的表格宽度（twips） */
    private TblWidth tblWidth(long dxa) {
        TblWidth w = WML.createTblWidth();
        w.setType(TblWidth.TYPE_DXA);
        w.setW(BigInteger.valueOf(dxa));
        return w;
    }

    /** 代码高亮片段：text 可能含换行，color 为 github 主题 hex（无 #），bold/italic 为 token 自带样式 */
    private record CodeFragment(String text, String color, boolean bold, boolean italic) {
    }

    /**
     * 递归扁平化 code 元素：文本节点按上下文（继承的颜色/加粗/斜体）产出片段；
     * span.hljs-* 叠加自己的颜色与 section/strong/emphasis 样式后继续下钻。
     */
    private void collectCodeFragments(Node node, String color, boolean bold, boolean italic, List<CodeFragment> out) {
        if (node instanceof TextNode tn) {
            String text = tn.getWholeText();
            if (!text.isEmpty()) {
                out.add(new CodeFragment(text, color, bold, italic));
            }
            return;
        }
        if (!(node instanceof Element el)) {
            return;
        }
        String nextColor = color;
        boolean nextBold = bold;
        boolean nextItalic = italic;
        if (el.tagName().equalsIgnoreCase("span")) {
            String cls = el.className();
            String c = hljsColor(cls);
            if (c != null) {
                nextColor = c;
            }
            if (hasHljsToken(cls, "section") || hasHljsToken(cls, "strong")) {
                nextBold = true;
            }
            if (hasHljsToken(cls, "emphasis")) {
                nextItalic = true;
            }
        }
        for (Node child : el.childNodes()) {
            collectCodeFragments(child, nextColor, nextBold, nextItalic, out);
        }
    }

    private void addCodeRun(P p, CodeFragment f, InlineStyle style) {
        R r = WML.createR();
        r.setRPr(buildRunPr(style.withCode(), false, f.color(), f.bold(), f.italic()));
        Text t = WML.createText();
        t.setSpace("preserve");
        t.setValue(f.text());
        r.getContent().add(t);
        p.getContent().add(r);
    }

    /** 按 github 亮色主题把 hljs token 类映射为颜色（hex 无 #）；未命中返回 null 走默认正文色 */
    private static String hljsColor(String className) {
        if (className == null) {
            return null;
        }
        for (String part : className.trim().split("\\s+")) {
            String token = part.startsWith("hljs-") ? part.substring(5) : part;
            switch (token) {
                case "keyword", "doctag", "template-tag", "template-variable", "type" -> {
                    return "D73A49";
                }
                case "title" -> {
                    return "6F42C1";
                }
                case "string", "regexp" -> {
                    return "032F62";
                }
                case "number", "literal", "attr", "attribute", "operator", "variable", "meta",
                     "selector-attr", "selector-class", "selector-id" -> {
                    return "005CC5";
                }
                case "built_in", "symbol" -> {
                    return "E36209";
                }
                case "comment", "code", "formula" -> {
                    return "6A737D";
                }
                case "name", "quote", "selector-tag", "selector-pseudo" -> {
                    return "22863A";
                }
                case "addition" -> {
                    return "22863A";
                }
                case "deletion" -> {
                    return "B31D28";
                }
                default -> {
                    // 继续看下一个 token 类
                }
            }
        }
        return null;
    }

    private static boolean hasHljsToken(String className, String token) {
        if (className == null) {
            return false;
        }
        for (String part : className.trim().split("\\s+")) {
            String t = part.startsWith("hljs-") ? part.substring(5) : part;
            if (t.equals(token)) {
                return true;
            }
        }
        return false;
    }

    private boolean isInlineTag(String tag) {
        return switch (tag.toLowerCase(Locale.ROOT)) {
            case "strong", "b", "em", "i", "s", "del", "strike", "u", "ins", "code", "a",
                 "span", "br", "img", "mark", "sub", "sup" -> true;
            default -> false;
        };
    }

    /**
     * 列表：li → 「• / 1.」前缀段落（原生 numbering part 成本高，前缀法跨阅读器最稳）；
     * li 内嵌套列表缩进 +1 递归。任务列表项（taskItem）用 ☑/☐ 前缀。
     */
    private void buildList(WordprocessingMLPackage pkg, Element list, boolean ordered, int indent,
                           InlineStyle style, boolean inCell, List<Object> out) {
        int index = 1;
        for (Element li : list.children()) {
            if (!li.tagName().equalsIgnoreCase("li")) {
                continue;
            }
            boolean checked = li.hasAttr("data-checked") && !"false".equals(li.attr("data-checked"));
            boolean taskItem = li.hasAttr("data-type") && "taskItem".equals(li.attr("data-type"));
            String prefix = taskItem ? (checked ? "☑ " : "☐ ")
                    : ordered ? (index + ". ") : "• ";
            // li 直属行内内容 + 第一层 <p>/<div> 的文本作为列表项正文
            P p = newParagraph(indent, style.quote());
            Element contentWrap = li.selectFirst("div, p");
            if (contentWrap != null && !contentWrap.tagName().equalsIgnoreCase("p")
                    && contentWrap.selectFirst("p, ul, ol, table") != null) {
                // div 包裹多块（taskItem 结构）：取其中块级逐个输出，首个加前缀。
                // p 仅在真正写入过内容时输出——taskItem 的 div 常只含块级子节点（<p>item</p>），
                // 此时 p 全程是空段落，无条件 out.add(p) 会在正文后多出一个空行（导出多空行根因）。
                boolean first = true;
                boolean pDirty = false;
                for (Element child : contentWrap.children()) {
                    if (isInlineTag(child.tagName())) {
                        if (first) {
                            addTextRun(p, prefix, style, false);
                            first = false;
                        }
                        addInlines(pkg, p, child, style, inCell);
                        pDirty = true;
                    } else {
                        List<Object> blocks = buildBlock(pkg, child, first ? indent : indent + 1, style, inCell);
                        if (first && !blocks.isEmpty() && blocks.get(0) instanceof P fp) {
                            insertPrefix(fp, prefix, style);
                            first = false;
                        }
                        out.addAll(blocks);
                    }
                }
                if (first) {
                    addTextRun(p, prefix, style, false);
                    pDirty = true;
                }
                if (pDirty) {
                    out.add(p);
                }
            } else {
                addTextRun(p, prefix, style, false);
                Element inner = contentWrap != null ? contentWrap : li;
                addInlines(pkg, p, inner, style, inCell, true);
                out.add(p);
            }
            // 嵌套列表（跳过已作为 contentWrap 处理的 div 内部）
            for (Element nested : li.children()) {
                String nt = nested.tagName().toLowerCase(Locale.ROOT);
                if ((nt.equals("ul") || nt.equals("ol"))) {
                    buildList(pkg, nested, nt.equals("ol"), indent + 1, style, inCell, out);
                }
            }
            index++;
        }
    }

    /**
     * 表格（需求3修复）：只取直接子行（嵌套表格行不混入）；colspan/rowspan 展开为占位矩阵，
     * 生成正确的 TblGrid + gridSpan + vMerge；固定布局等宽列；表头（th/thead）加底色。
     * 嵌套在单元格内的表格降级为文本行（| 分隔）。
     */
    private Tbl buildTable(WordprocessingMLPackage pkg, Element tableEl, InlineStyle style) {
        // 直接子行：table 直接子 tr + thead/tbody/tfoot 下的 tr（select("tr") 会误收嵌套表格的行）
        List<Element> trs = new ArrayList<>();
        for (Element section : tableEl.children()) {
            String st = section.tagName().toLowerCase(Locale.ROOT);
            if (st.equals("tr")) {
                trs.add(section);
            } else if (st.equals("thead") || st.equals("tbody") || st.equals("tfoot")) {
                for (Element tr : section.children()) {
                    if (tr.tagName().equalsIgnoreCase("tr")) {
                        trs.add(tr);
                    }
                }
            }
        }
        // 占位矩阵：colspan/rowspan 展开，记录每格归属
        List<List<CellSlot>> grid = new ArrayList<>();
        for (int r = 0; r < trs.size(); r++) {
            Element tr = trs.get(r);
            boolean inHead = tr.parent() != null && tr.parent().tagName().equalsIgnoreCase("thead");
            while (grid.size() <= r) {
                grid.add(new ArrayList<>());
            }
            List<CellSlot> rowSlots = grid.get(r);
            int c = 0;
            for (Element cell : tr.children()) {
                if (!cell.tagName().equalsIgnoreCase("td") && !cell.tagName().equalsIgnoreCase("th")) {
                    continue;
                }
                while (c < rowSlots.size() && rowSlots.get(c) != null) {
                    c++;
                }
                int colSpan = Math.max(1, parseSpan(cell.attr("colspan")));
                int rowSpan = Math.max(1, parseSpan(cell.attr("rowspan")));
                boolean header = inHead || cell.tagName().equalsIgnoreCase("th");
                for (int i = 0; i < rowSpan; i++) {
                    while (grid.size() <= r + i) {
                        grid.add(new ArrayList<>());
                    }
                    List<CellSlot> targetRow = grid.get(r + i);
                    while (targetRow.size() < c + colSpan) {
                        targetRow.add(null);
                    }
                    for (int j = 0; j < colSpan; j++) {
                        targetRow.set(c + j, new CellSlot(cell, i == 0 && j == 0, i == 0,
                                header, rowSpan, colSpan));
                    }
                }
                c += colSpan;
            }
        }
        int colCount = 0;
        for (List<CellSlot> rowSlots : grid) {
            colCount = Math.max(colCount, rowSlots.size());
        }
        if (colCount == 0) {
            colCount = 1;
        }

        Tbl tbl = WML.createTbl();
        TblPr tblPr = WML.createTblPr();
        TblBorders borders = WML.createTblBorders();
        borders.setTop(border(STBorder.SINGLE, BORDER_COLOR));
        borders.setBottom(border(STBorder.SINGLE, BORDER_COLOR));
        borders.setLeft(border(STBorder.SINGLE, BORDER_COLOR));
        borders.setRight(border(STBorder.SINGLE, BORDER_COLOR));
        borders.setInsideH(border(STBorder.SINGLE, BORDER_COLOR));
        borders.setInsideV(border(STBorder.SINGLE, BORDER_COLOR));
        tblPr.setTblBorders(borders);
        // 固定布局：列宽由 TblGrid 决定，gridSpan 合并列不串位
        CTTblLayoutType layout = WML.createCTTblLayoutType();
        layout.setType(STTblLayoutType.FIXED);
        tblPr.setTblLayout(layout);
        TblWidth tblW = WML.createTblWidth();
        tblW.setType(TblWidth.TYPE_DXA);
        tblW.setW(BigInteger.valueOf(TABLE_TOTAL_WIDTH_TWIPS));
        tblPr.setTblW(tblW);
        tbl.setTblPr(tblPr);
        // 列宽：优先按 colgroup（Tiptap resizable 表格输出各列 px 宽）比例分配总宽；缺失退回等宽
        int[] colWidths = computeColWidths(tableEl, colCount);
        TblGrid tblGrid = WML.createTblGrid();
        for (int c = 0; c < colCount; c++) {
            TblGridCol col = WML.createTblGridCol();
            col.setW(BigInteger.valueOf(colWidths[c]));
            tblGrid.getGridCol().add(col);
        }
        tbl.setTblGrid(tblGrid);

        for (List<CellSlot> rowSlots : grid) {
            Tr row = WML.createTr();
            for (int c = 0; c < colCount; c++) {
                CellSlot slot = c < rowSlots.size() ? rowSlots.get(c) : null;
                if (slot == null) {
                    // 结构残缺的空位：补空单元格防止错位
                    Tc tc = WML.createTc();
                    tc.setTcPr(cellTcPr(colWidths[c], 1, 1, false, false));
                    tc.getContent().add(newParagraph(0, false));
                    row.getContent().add(tc);
                    continue;
                }
                if (!slot.origin()) {
                    if (slot.originRow()) {
                        // 被起始单元格的 gridSpan 覆盖，跳过
                        continue;
                    }
                    // rowspan 延续单元格
                    Tc tc = WML.createTc();
                    tc.setTcPr(cellTcPr(colWidths[c], 1, slot.rowSpan(), false, true));
                    tc.getContent().add(newParagraph(0, false));
                    row.getContent().add(tc);
                    continue;
                }
                Tc tc = WML.createTc();
                // 合并格宽 = 起始列起 colSpan 列宽之和
                int spannedWidth = 0;
                for (int k = c; k < Math.min(c + slot.colSpan(), colCount); k++) {
                    spannedWidth += colWidths[k];
                }
                tc.setTcPr(cellTcPr(spannedWidth, slot.colSpan(), slot.rowSpan(), slot.header(), false));
                InlineStyle cellStyle = slot.header() ? style.withBold() : style;
                fillCellContent(pkg, tc, slot.cell(), cellStyle);
                row.getContent().add(tc);
            }
            tbl.getContent().add(row);
        }
        return tbl;
    }

    /**
     * 占位矩阵单元：origin=合并起始格，originRow=位于起始格所在行（其余为 rowspan 延续）
     */
    private record CellSlot(Element cell, boolean origin, boolean originRow, boolean header,
                            int rowSpan, int colSpan) {
    }

    /**
     * 列宽（twips，总和≈TABLE_TOTAL_WIDTH_TWIPS）：按 colgroup > col 的 px 宽等比缩放；
     * 个别列无宽度用已解析列均值补；全无 colgroup/宽度时退回等宽。最小列宽保护防塌成 0。
     */
    private static int[] computeColWidths(Element tableEl, int colCount) {
        int[] widths = new int[colCount];
        int equal = TABLE_TOTAL_WIDTH_TWIPS / colCount;
        double[] px = new double[colCount];
        double parsedSum = 0;
        int parsedCount = 0;
        Elements cols = tableEl.select("> colgroup > col");
        for (int c = 0; c < cols.size() && c < colCount; c++) {
            Double w = parseColWidthPx(cols.get(c));
            if (w != null && w > 0) {
                px[c] = w;
                parsedSum += w;
                parsedCount++;
            }
        }
        if (parsedCount == 0) {
            Arrays.fill(widths, equal);
            widths[colCount - 1] = TABLE_TOTAL_WIDTH_TWIPS - equal * (colCount - 1);
            return widths;
        }
        double avg = parsedSum / parsedCount;
        double total = 0;
        for (int c = 0; c < colCount; c++) {
            total += px[c] > 0 ? px[c] : avg;
        }
        int sum = 0;
        for (int c = 0; c < colCount; c++) {
            double w = px[c] > 0 ? px[c] : avg;
            widths[c] = Math.max(MIN_COL_WIDTH_TWIPS, (int) Math.round(TABLE_TOTAL_WIDTH_TWIPS * w / total));
            sum += widths[c];
        }
        // 总宽对齐：舍入差额落在最后一列
        widths[colCount - 1] = Math.max(MIN_COL_WIDTH_TWIPS,
                widths[colCount - 1] + (TABLE_TOTAL_WIDTH_TWIPS - sum));
        return widths;
    }

    /**
     * col 元素宽度 px：style "width:123px" 优先，其次 width 属性（"123"/"123px"）；解析失败 null
     */
    private static Double parseColWidthPx(Element col) {
        String style = col.attr("style");
        if (style != null && !style.isBlank()) {
            Matcher m = IMG_WIDTH_STYLE.matcher(style);
            if (m.find()) {
                try {
                    return Double.parseDouble(m.group(1));
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            }
        }
        String w = col.attr("width");
        if (w != null && !w.isBlank()) {
            try {
                return Double.parseDouble(w.replaceAll("[^\\d.]", ""));
            } catch (NumberFormatException ignored) {
                // 解析失败当无宽度
            }
        }
        return null;
    }

    /**
     * 单元格属性：宽度（dxa）+ gridSpan + vMerge + 表头底色
     */
    private TcPr cellTcPr(int colWidth, int colSpan, int rowSpan, boolean header, boolean vMergeContinue) {
        TcPr tcPr = WML.createTcPr();
        TblWidth tcW = WML.createTblWidth();
        tcW.setType(TblWidth.TYPE_DXA);
        tcW.setW(BigInteger.valueOf((long) colWidth * colSpan));
        tcPr.setTcW(tcW);
        if (colSpan > 1) {
            TcPrInner.GridSpan gs = WML.createTcPrInnerGridSpan();
            gs.setVal(BigInteger.valueOf(colSpan));
            tcPr.setGridSpan(gs);
        }
        if (vMergeContinue) {
            TcPrInner.VMerge vm = WML.createTcPrInnerVMerge();
            vm.setVal("continue");
            tcPr.setVMerge(vm);
        } else if (rowSpan > 1) {
            TcPrInner.VMerge vm = WML.createTcPrInnerVMerge();
            vm.setVal("restart");
            tcPr.setVMerge(vm);
        }
        if (header) {
            CTShd shd = WML.createCTShd();
            shd.setFill("F5F6F7");
            tcPr.setShd(shd);
        }
        return tcPr;
    }

    /**
     * 单元格内容：块级子元素递归构建；行内混排包一个段落
     */
    private void fillCellContent(WordprocessingMLPackage pkg, Tc tc, Element cell, InlineStyle cellStyle) {
        List<Object> cellBlocks = new ArrayList<>();
        for (Element child : cell.children()) {
            if (isInlineTag(child.tagName())) {
                P p = newParagraph(0, false);
                addInlines(pkg, p, cell, cellStyle, true);
                cellBlocks.add(p);
                break;
            }
            cellBlocks.addAll(buildBlock(pkg, child, 0, cellStyle, true));
        }
        if (cellBlocks.isEmpty()) {
            cellBlocks.add(newParagraph(0, false));
        }
        cellBlocks.forEach(tc.getContent()::add);
    }

    private static int parseSpan(String attr) {
        try {
            return attr == null || attr.isBlank() ? 1 : Integer.parseInt(attr.trim());
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    // ============================ 行内构建 ============================

    /**
     * 遍历元素的子节点，把行内内容追加到段落（跳过块级子元素，除非 skipBlocks=false 时遇块即停由调用方保证）
     */
    private void addInlines(WordprocessingMLPackage pkg, P p, Element el, InlineStyle style, boolean inCell) {
        addInlines(pkg, p, el, style, inCell, false);
    }

    private void addInlines(WordprocessingMLPackage pkg, P p, Element el, InlineStyle style, boolean inCell, boolean stopAtBlock) {
        for (Node child : el.childNodes()) {
            if (child instanceof Element ce && !isInlineTag(ce.tagName())) {
                if (stopAtBlock) {
                    return;
                }
                continue;
            }
            addInlineNode(pkg, p, child, style, inCell);
        }
    }

    private void addInlineNode(WordprocessingMLPackage pkg, P p, Node node, InlineStyle style, boolean inCell) {
        if (node instanceof TextNode tn) {
            String text = tn.getWholeText();
            if (!text.isEmpty()) {
                addTextRun(p, text, style, false);
            }
            return;
        }
        if (!(node instanceof Element el)) {
            return;
        }
        String tag = el.tagName().toLowerCase(Locale.ROOT);
        switch (tag) {
            case "strong", "b" -> addInlines(pkg, p, el, style.withBold(), inCell);
            case "em", "i" -> addInlines(pkg, p, el, style.withItalic(), inCell);
            case "s", "del", "strike" -> addInlines(pkg, p, el, style.withStrike(), inCell);
            case "u", "ins" -> addInlines(pkg, p, el, style.withUnderline(), inCell);
            case "code" -> addInlines(pkg, p, el, style.withCode(), inCell);
            case "mark" -> {
                // 高亮：data-color（自有 renderHTML 写入，无损）优先，其次 style background-color
                String hl = cssToHex(el.attr("data-color"));
                if (hl == null) {
                    hl = stylePropToHex(el.attr("style"), "background-color");
                }
                addInlines(pkg, p, el, style.withShade(hl), inCell);
            }
            case "br" -> {
                R r = WML.createR();
                Br br = WML.createBr();
                r.getContent().add(br);
                p.getContent().add(r);
            }
            case "a" -> {
                // 链接：有 href 建 external hyperlink（附件下载/正文普通链接都走这里），无 href 仅下划线
                String href = el.attr("href");
                if (href != null && !href.isBlank() && !"#".equals(href)) {
                    addHyperlinkRun(pkg, p, href, el.text(), style);
                } else {
                    addInlines(pkg, p, el, style.withUnderline(), inCell);
                }
            }
            case "img" -> {
                // 表格单元格内跳过图片：docx 单元格里插浮动图片排版会错乱，历史上就是不支持的。
                // 但**公式必须豁免**（data-math 标记，由 preprocess 的公式 pass 打上）：公式是被
                // 替换成 img 的，若也一起跳过，表格里的公式会整个消失——连降级文本都不剩，因为
                // preprocess 已经把节点文本清空换成图片了。addImageRun 本身只往 P 里加 run，
                // 在单元格里是安全的。
                if (!inCell || el.hasAttr("data-math")) {
                    addImageRun(pkg, p, el);
                }
            }
            default -> {
                // @用户 mention：docx 无徽章控件，用编辑态同款配色（蓝字 3370FF + 浅蓝底 E8F0FF）近似
                if ("mention".equals(el.attr("data-type")) || el.hasClass("mention")) {
                    InlineStyle ms = style.withColor(MENTION_COLOR).withShade(MENTION_SHADE);
                    addTextRun(p, el.text(), ms, false);
                    return;
                }
                // TextStyle/Color 扩展的 span：读行内 style 的文字色/背景色（transparent/inherit 忽略）
                String st = el.attr("style");
                InlineStyle s = style
                        .withColor(stylePropToHex(st, "color"))
                        .withShade(stylePropToHex(st, "background-color"));
                addInlines(pkg, p, el, s, inCell);
            }
        }
    }

    /**
     * 从 CSS style 属性提取指定属性值并转 docx hex（无 #）；缺失/transparent/inherit/无法解析返回 null
     */
    private static String stylePropToHex(String style, String prop) {
        if (style == null || style.isBlank()) {
            return null;
        }
        for (String part : style.split(";")) {
            int idx = part.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            if (part.substring(0, idx).trim().equalsIgnoreCase(prop)) {
                return cssToHex(part.substring(idx + 1).trim());
            }
        }
        return null;
    }

    /**
     * CSS 颜色 → docx 6 位 hex（无 #）：支持 #rgb/#rrggbb/rgb()/rgba()；
     * transparent/inherit/rgba 全透明返回 null；解析失败返回 null（不着色，不炸导出）
     */
    private static String cssToHex(String css) {
        if (css == null || css.isBlank()) {
            return null;
        }
        String v = css.trim().toLowerCase(Locale.ROOT);
        if (v.equals("transparent") || v.equals("inherit") || v.equals("initial") || v.equals("unset")) {
            return null;
        }
        if (v.startsWith("#")) {
            String h = v.substring(1);
            if (h.length() == 3) {
                StringBuilder sb = new StringBuilder(6);
                for (int i = 0; i < 3; i++) {
                    sb.append(h.charAt(i)).append(h.charAt(i));
                }
                h = sb.toString();
            }
            return h.length() == 6 && h.matches("[0-9a-f]{6}") ? h.toUpperCase(Locale.ROOT) : null;
        }
        Matcher m = CSS_RGB.matcher(v);
        if (m.matches()) {
            // rgba(...,0) 视为透明
            if (m.group(4) != null) {
                try {
                    if (Double.parseDouble(m.group(4).trim()) <= 0) {
                        return null;
                    }
                } catch (NumberFormatException ignored) {
                    // alpha 解析失败按不透明处理
                }
            }
            try {
                int r = Integer.parseInt(m.group(1).trim());
                int g = Integer.parseInt(m.group(2).trim());
                int b = Integer.parseInt(m.group(3).trim());
                if (r < 0 || r > 255 || g < 0 || g > 255 || b < 0 || b > 255) {
                    return null;
                }
                return String.format(Locale.ROOT, "%02X%02X%02X", r, g, b);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private void addTextRun(P p, String text, InlineStyle style, boolean monospace) {
        R r = WML.createR();
        r.setRPr(buildRunPr(style, monospace));
        Text t = WML.createText();
        t.setSpace("preserve");
        t.setValue(text);
        r.getContent().add(t);
        p.getContent().add(r);
    }

    /**
     * docx 外部超链接 run：在 MainDocumentPart 关系里挂一个 external hyperlink 关系，
     * 再输出 w:hyperlink 包一个蓝色下划线 run。Word/WPS 里 Ctrl+点击跳转、默认蓝色下划线。
     * 建立关系失败（如关系 id 冲突）降级为下划线纯文本，保证导出不因链接中断。
     */
    private void addHyperlinkRun(WordprocessingMLPackage pkg, P p, String url, String text, InlineStyle style) {
        try {
            Relationship rel = new Relationship();
            rel.setType(Namespaces.HYPERLINK);
            rel.setTarget(url);
            rel.setTargetMode("External");
            rel.setId(pkg.getMainDocumentPart().getRelationshipsPart().getNextId());
            pkg.getMainDocumentPart().getRelationshipsPart().addRelationship(rel);

            P.Hyperlink link = new P.Hyperlink();
            link.setId(rel.getId());
            R r = WML.createR();
            r.setRPr(buildRunPr(style.withUnderline(), false, "0563C1", false, false));
            Text t = WML.createText();
            t.setSpace("preserve");
            t.setValue(text);
            r.getContent().add(t);
            link.getContent().add(r);
            p.getContent().add(link);
        } catch (Exception e) {
            log.warn("docx hyperlink fallback to text: {}", e.getMessage());
            addTextRun(p, text, style.withUnderline(), false);
        }
    }

    /**
     * data URI 图片 → docx 内嵌图（等比缩放到最大宽度内）；失败静默跳过
     */
    private void addImageRun(WordprocessingMLPackage pkg, P p, Element img) {
        try {
            String src = img.attr("src");
            int comma = src.indexOf(',');
            if (!src.startsWith("data:") || comma < 0) {
                return;
            }
            byte[] bytes = Base64.getDecoder().decode(src.substring(comma + 1));
            BinaryPartAbstractImage imagePart = BinaryPartAbstractImage.createImagePart(pkg, bytes);
            long id = imageSeq.incrementAndGet();
            org.docx4j.dml.wordprocessingDrawing.Inline inline =
                    imagePart.createImageInline("image", "image", id, (int) id);
            long naturalCx = inline.getExtent().getCx();
            long naturalCy = inline.getExtent().getCy();
            long cx;
            long cy;
            // 需求5：有显式宽度按文档设置（CSS px → EMU，1 英寸 = 96 px）等比缩放；
            // 否则保持原有「自然尺寸，超 6 英寸才压缩」的兜底。
            Long explicitPx = parseImageWidthPx(img);
            if (explicitPx != null && explicitPx > 0 && naturalCx > 0 && naturalCy > 0) {
                cx = explicitPx * 914400L / 96L;
                cy = cx * naturalCy / naturalCx;
            } else {
                cx = naturalCx;
                cy = naturalCy;
                if (cx > DOCX_MAX_IMAGE_CX) {
                    cy = cy * DOCX_MAX_IMAGE_CX / cx;
                    cx = DOCX_MAX_IMAGE_CX;
                }
            }
            inline.getExtent().setCx(cx);
            inline.getExtent().setCy(cy);
            R r = WML.createR();
            org.docx4j.wml.Drawing drawing = WML.createDrawing();
            drawing.getAnchorOrInline().add(inline);
            r.getContent().add(drawing);
            p.getContent().add(r);
        } catch (Exception e) {
            log.warn("docx inline image skipped: {}", e.getMessage());
        }
    }

    /**
     * 解析图片显式宽度（CSS px）：优先 style 里 width:Npx（排除 max-width/min-width），
     * 其次 width 属性；无显式宽度返回 null（走自然尺寸兜底）。
     */
    private static Long parseImageWidthPx(Element img) {
        String style = img.attr("style");
        if (style != null && !style.isBlank()) {
            Matcher m = IMG_WIDTH_STYLE.matcher(style);
            if (m.find()) {
                try {
                    return Math.round(Double.parseDouble(m.group(1)));
                } catch (NumberFormatException ignored) {
                    // fall through to width attr
                }
            }
        }
        String width = img.attr("width");
        if (width != null && !width.isBlank()) {
            try {
                return Long.parseLong(width.trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return null;
    }

    // ============================ docx 基础件 ============================

    private P newParagraph(int indent, boolean quote) {
        P p = WML.createP();
        if (indent > 0 || quote) {
            PPr ppr = ensurePPr(p);
            PPrBase.Ind ind = WML.createPPrBaseInd();
            ind.setLeft(BigInteger.valueOf((indent * 480L) + (quote ? 240L : 0L)));
            ppr.setInd(ind);
            if (quote) {
                PPrBase.PBdr bdr = WML.createPPrBasePBdr();
                bdr.setLeft(border(STBorder.SINGLE, "C9CDD4"));
                ppr.setPBdr(bdr);
            }
        }
        return p;
    }

    private void insertPrefix(P p, String prefix, InlineStyle style) {
        R r = WML.createR();
        r.setRPr(buildRunPr(style));
        Text t = WML.createText();
        t.setSpace("preserve");
        t.setValue(prefix);
        r.getContent().add(t);
        p.getContent().add(0, r);
    }

    private PPr ensurePPr(P p) {
        if (p.getPPr() == null) {
            p.setPPr(WML.createPPr());
        }
        return p.getPPr();
    }

    private CTBorder border(STBorder style, String color) {
        CTBorder b = WML.createCTBorder();
        b.setVal(style);
        b.setSz(BigInteger.valueOf(4));
        b.setColor(color);
        b.setSpace(BigInteger.ZERO);
        return b;
    }

    private RPr buildRunPr(InlineStyle style) {
        return buildRunPr(style, false);
    }

    private RPr buildRunPr(InlineStyle style, boolean monospace) {
        RPr rpr = WML.createRPr();
        if (style.bold()) {
            rpr.setB(new BooleanDefaultTrue());
        }
        if (style.italic()) {
            rpr.setI(new BooleanDefaultTrue());
        }
        if (style.strike()) {
            rpr.setStrike(new BooleanDefaultTrue());
        }
        if (style.underline()) {
            org.docx4j.wml.U u = WML.createU();
            u.setVal(org.docx4j.wml.UnderlineEnumeration.SINGLE);
            rpr.setU(u);
        }
        if (style.code() || monospace) {
            RFonts fonts = WML.createRFonts();
            fonts.setAscii("Courier New");
            fonts.setHAnsi("Courier New");
            rpr.setRFonts(fonts);
        }
        // 背景：用户显式高亮优先，其次代码块灰底
        String shade = style.shade() != null ? style.shade() : ((style.code() || monospace) ? CODE_SHADE : null);
        if (shade != null) {
            CTShd shd = WML.createCTShd();
            shd.setFill(shade);
            rpr.setShd(shd);
        }
        // 文字色：用户显式颜色优先，其次引用灰
        String color = style.color() != null ? style.color() : (style.quote() ? QUOTE_COLOR : null);
        if (color != null) {
            org.docx4j.wml.Color c = WML.createColor();
            c.setVal(color);
            rpr.setColor(c);
        }
        int size = style.sizeHalfPt() != null ? style.sizeHalfPt() : BODY_SIZE_HALF_PT;
        HpsMeasure sz = WML.createHpsMeasure();
        sz.setVal(BigInteger.valueOf(size));
        rpr.setSz(sz);
        rpr.setSzCs(sz);
        return rpr;
    }

    /**
     * 代码高亮 run 属性：在 monospace/code 底子上叠加 token 颜色与加粗/斜体。
     * color 为 github 主题 hex（无 #），null 表示不设色。
     */
    private RPr buildRunPr(InlineStyle style, boolean monospace, String color, boolean bold, boolean italic) {
        RPr rpr = buildRunPr(style, monospace);
        if (bold) {
            rpr.setB(new BooleanDefaultTrue());
        }
        if (italic) {
            rpr.setI(new BooleanDefaultTrue());
        }
        if (color != null && !color.isBlank()) {
            org.docx4j.wml.Color c = WML.createColor();
            c.setVal(color);
            rpr.setColor(c);
        }
        return rpr;
    }

    // ============================ pdf 字体 ============================

    /**
     * 中文字体落磁盘（OpenPDF 需要路径），进程内仅一次
     */
    private String ensureFontFile() throws Exception {
        String path = fontFilePath;
        if (path != null) {
            return path;
        }
        synchronized (FONT_LOCK) {
            if (fontFilePath != null) {
                return fontFilePath;
            }
            Path temp = Files.createTempFile("repo-font-wqy-", ".ttc");
            temp.toFile().deleteOnExit();
            try (InputStream in = new ClassPathResource(FONT_CLASSPATH).getInputStream()) {
                Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            fontFilePath = temp.toAbsolutePath().toString();
            return fontFilePath;
        }
    }
}
