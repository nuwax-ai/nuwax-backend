package com.xspaceagi.repo.application.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepoDocConvertServiceTest {

    private final RepoDocConvertService service = new RepoDocConvertService();

    @Test
    void markdownToHtmlRendersBasicSyntax() {
        String html = service.markdownToHtml("# 标题\n\n一段 **加粗** 文本\n\n- 列表项\n");
        assertTrue(html.contains("<h1>标题</h1>"));
        assertTrue(html.contains("<strong>加粗</strong>"));
        assertTrue(html.contains("<li>列表项</li>"));
    }

    @Test
    void markdownToHtmlSupportsTables() {
        String md = "| A | B |\n|---|---|\n| 1 | 2 |\n";
        String html = service.markdownToHtml(md);
        assertTrue(html.contains("<table>"));
        assertTrue(html.contains("<td>1</td>"));
    }

    @Test
    void htmlToMarkdownRoundTrip() {
        String md = service.htmlToMarkdown("<h1>标题</h1><p>Hello <strong>world</strong></p>");
        assertTrue(md.contains("# 标题"));
        assertTrue(md.contains("**world**"));
    }

    @Test
    void nullAndEmptySafe() {
        assertEquals("", service.markdownToHtml(null));
        assertEquals("", service.markdownToHtml(""));
        assertEquals("", service.htmlToMarkdown(null));
        assertEquals("", service.htmlToMarkdown(""));
    }
}
