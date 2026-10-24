package com.xspaceagi.knowledge;

import com.xspaceagi.knowledge.domain.docparser.SmartParsingModelUnavailableException;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * knowledge_task.result 失败原因的结构化编码与按查看者语言渲染测试。
 * 覆盖: encode 格式、zh-CN/英文/无上下文三种渲染、历史明文与非法输入兼容、
 * SmartParsingModelUnavailableException 携带 code+params。
 */
public class KnowledgeTaskFailureReasonTest {

    @AfterEach
    public void tearDown() {
        RequestContext.remove();
    }

    private static void setLang(String lang) {
        RequestContext<Object> ctx = new RequestContext<>();
        ctx.setLang(lang);
        RequestContext.set(ctx);
    }

    @Test
    public void encodeWithoutParamsReturnsBareCode() {
        assertEquals("9391", BizExceptionCodeEnum.agentTenantDefaultModelMissing.encode());
        assertEquals("9391", BizExceptionCodeEnum.agentTenantDefaultModelMissing.encode((Object[]) null));
    }

    @Test
    public void encodeWithParamsJoinsByColon() {
        assertEquals("9390:281", BizExceptionCodeEnum.agentModelConfigMissing.encode(281L));
    }

    @Test
    public void renderStructuredInChineseWhenLangIsZhCN() {
        setLang("zh-CN");
        String rendered = BizExceptionCodeEnum.renderIfStructured("9390:281");
        assertTrue(rendered.contains("281"), "应包含模型ID参数: " + rendered);
        assertTrue(rendered.contains("模型管理"), "应包含中文行动指引: " + rendered);
    }

    @Test
    public void renderStructuredInEnglishForNonZhLang() {
        setLang("en-US");
        String rendered = BizExceptionCodeEnum.renderIfStructured("9390:281");
        assertTrue(rendered.contains("281"), "应包含模型ID参数: " + rendered);
        assertTrue(rendered.contains("Model Management"), "应渲染英文模板: " + rendered);
    }

    @Test
    public void renderWithoutContextFallsBackToEnglish() {
        RequestContext.remove();
        String rendered = BizExceptionCodeEnum.renderIfStructured("9391");
        assertTrue(rendered.contains("Model Management"), "无请求上下文按英文兜底: " + rendered);
    }

    @Test
    public void renderPassesThroughHistoricalPlainText() {
        String stored = "Model does not exist or has been deleted: 281";
        assertEquals(stored, BizExceptionCodeEnum.renderIfStructured(stored));
        assertEquals("重试次数达到最大值", BizExceptionCodeEnum.renderIfStructured("重试次数达到最大值"));
    }

    @Test
    public void renderPassesThroughUnknownCodeAndEdgeInputs() {
        assertEquals("9999:x", BizExceptionCodeEnum.renderIfStructured("9999:x"));
        assertEquals("9999", BizExceptionCodeEnum.renderIfStructured("9999"));
        assertEquals("解析超时", BizExceptionCodeEnum.renderIfStructured("解析超时"));
        assertNull(BizExceptionCodeEnum.renderIfStructured(null));
        assertEquals("", BizExceptionCodeEnum.renderIfStructured(""));
    }

    @Test
    public void exceptionCarriesCodeAndParams() {
        SmartParsingModelUnavailableException withModel =
                new SmartParsingModelUnavailableException(BizExceptionCodeEnum.agentModelConfigMissing, 281L);
        assertEquals("9390", withModel.getCode());
        assertEquals("9390:281", withModel.encodedResult());

        SmartParsingModelUnavailableException defaultOne = new SmartParsingModelUnavailableException();
        assertEquals("9391", defaultOne.getCode());
        assertEquals("9391", defaultOne.encodedResult());
    }

    @Test
    public void encodedResultRendersBackToLocalizedMessage() {
        setLang("zh-CN");
        SmartParsingModelUnavailableException e =
                new SmartParsingModelUnavailableException(BizExceptionCodeEnum.agentModelConfigMissing, 281L);
        String rendered = BizExceptionCodeEnum.renderIfStructured(e.encodedResult());
        assertTrue(rendered.contains("281"), "roundtrip 应保留参数: " + rendered);
        assertTrue(rendered.contains("模型配置无效"), "roundtrip 应渲染中文模板: " + rendered);
    }
}
