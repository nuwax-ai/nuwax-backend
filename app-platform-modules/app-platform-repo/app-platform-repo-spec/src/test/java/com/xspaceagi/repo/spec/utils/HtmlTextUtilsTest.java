package com.xspaceagi.repo.spec.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlTextUtilsTest {

    @Test
    void nullAndEmptyReturnEmpty() {
        assertEquals("", HtmlTextUtils.extractText(null));
        assertEquals("", HtmlTextUtils.extractText(""));
    }

    @Test
    void stripsTagsAndDecodesEntities() {
        String html = "<p>你好 &amp; 世界</p><div>第二行&nbsp;文本</div>";
        assertEquals("你好 & 世界 第二行 文本", HtmlTextUtils.extractText(html));
    }

    @Test
    void removesScriptAndStyleBlocks() {
        String html = "<style>.a{color:red}</style><p>正文</p><script>var a=1;</script><p>结尾</p>";
        assertEquals("正文 结尾", HtmlTextUtils.extractText(html));
    }

    @Test
    void keepsBrSeparatedWords() {
        String html = "<p>段落一<br/>段落二</p>";
        assertEquals("段落一 段落二", HtmlTextUtils.extractText(html));
    }

    @Test
    void collapsesWhitespace() {
        String html = "<p>a</p>\n\n   <p>b</p>";
        assertEquals("a b", HtmlTextUtils.extractText(html));
    }

    @Test
    void truncatesVeryLongText() {
        String html = "<p>" + "长".repeat(200_000) + "</p>";
        assertTrue(HtmlTextUtils.extractText(html).length() <= 100_000);
    }
}
