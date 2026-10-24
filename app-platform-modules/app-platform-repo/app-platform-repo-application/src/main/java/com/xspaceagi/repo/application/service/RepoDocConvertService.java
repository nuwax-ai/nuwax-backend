package com.xspaceagi.repo.application.service;

import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 文档格式转换服务（flexmark）。
 * <p>
 * 导入：Markdown → HTML（前端将 HTML setContent 进编辑器）；
 * 导出：HTML快照 → Markdown（flexmark-html-converter，基于 jsoup）。
 * Word 导入后置（先保证 md/html 双向）。
 */
@Service
public class RepoDocConvertService {

    private static final Parser MARKDOWN_PARSER;

    private static final HtmlRenderer HTML_RENDERER;

    private static final FlexmarkHtmlConverter HTML_TO_MARKDOWN;

    static {
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, Arrays.asList(
                TablesExtension.create(),
                StrikethroughExtension.create()
        ));
        MARKDOWN_PARSER = Parser.builder(options).build();
        HTML_RENDERER = HtmlRenderer.builder(options).build();
        // ATX 风格标题（# / ##），避免 setext 下划线风格
        MutableDataSet convertOptions = new MutableDataSet();
        convertOptions.set(FlexmarkHtmlConverter.SETEXT_HEADINGS, false);
        HTML_TO_MARKDOWN = FlexmarkHtmlConverter.builder(convertOptions).build();
    }

    /**
     * Markdown 转 HTML 片段
     *
     * @param markdown Markdown 文本
     * @return HTML 片段
     */
    public String markdownToHtml(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        Node document = MARKDOWN_PARSER.parse(markdown);
        return HTML_RENDERER.render(document);
    }

    /**
     * HTML 转 Markdown
     *
     * @param html HTML 片段（页面 html_snapshot）
     * @return Markdown 文本
     */
    public String htmlToMarkdown(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        // 待办列表保真：flexmark html2md 不识别 Tiptap taskList（checkbox 埋在 li>div>label 里，
        // 会被当普通列表丢掉勾选状态）。若快照含 taskList，先用 jsoup 抽出每个 taskList 转成
        // markdown 任务列表（- [ ]/- [x]，含嵌套缩进），以唯一占位符替换，flexmark 转其余部分
        // 后再还原占位，保证 ☐/☑ 状态与层级保留（占位符走 Unicode 私有区字符，正文不会出现）。
        if (!html.contains("taskList")) {
            return HTML_TO_MARKDOWN.convert(html);
        }
        Document doc = Jsoup.parseBodyFragment(html);
        List<String> taskBlocks = new ArrayList<>();
        int index = 0;
        for (Element ul : doc.select("ul[data-type=taskList]")) {
            String placeholder = "TASKLIST" + index + "";
            taskBlocks.add(taskListToMarkdown(ul, 0));
            ul.replaceWith(new TextNode(placeholder));
            index++;
        }
        String converted = HTML_TO_MARKDOWN.convert(doc.body().html());
        for (int i = 0; i < taskBlocks.size(); i++) {
            converted = converted.replace("TASKLIST" + i + "", taskBlocks.get(i));
        }
        return converted;
    }

    /**
     * Tiptap taskList → markdown 任务列表（递归，含嵌套子任务缩进）。
     * 结构：ul[data-type=taskList] &gt; li[data-type=taskItem data-checked] &gt; div &gt; (label&gt;input + div&gt;p)。
     * li 自身文本取「移除嵌套 taskList 后的 text」（label/checkbox 无文本，不污染正文）；
     * 子任务取「最近 li 祖先 == 当前 li」的直接嵌套 ul，孙子层由递归调用自己处理，避免重复。
     */
    private static String taskListToMarkdown(Element ul, int depth) {
        StringBuilder sb = new StringBuilder();
        String indent = "    ".repeat(depth);
        for (Element li : ul.children()) {
            if (!"li".equals(li.tagName())) {
                continue;
            }
            boolean checked = "true".equals(li.attr("data-checked"));
            Element clone = li.clone();
            for (Element nested : clone.select("ul[data-type=taskList]")) {
                nested.remove();
            }
            String text = clone.text().replaceAll("\\s+", " ").trim();
            sb.append(indent).append(checked ? "- [x] " : "- [ ] ").append(text).append('\n');
            for (Element nestedUl : li.select("ul[data-type=taskList]")) {
                Element nearestLi = nestedUl.parents().stream()
                        .filter(p -> "li".equals(p.tagName()))
                        .findFirst()
                        .orElse(null);
                if (nearestLi == li) {
                    sb.append(taskListToMarkdown(nestedUl, depth + 1));
                }
            }
        }
        return sb.toString();
    }
}
