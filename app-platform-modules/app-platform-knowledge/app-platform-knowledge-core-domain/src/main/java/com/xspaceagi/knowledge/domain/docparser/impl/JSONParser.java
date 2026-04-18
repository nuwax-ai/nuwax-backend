package com.xspaceagi.knowledge.domain.docparser.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.knowledge.core.spec.utils.Constants;
import com.xspaceagi.knowledge.domain.docparser.parse.DocParser;
import com.xspaceagi.knowledge.domain.model.KnowledgeDocumentModel;
import com.xspaceagi.knowledge.domain.model.KnowledgeRawSegmentModel;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeRawSegmentRepository;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.KnowledgeException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class JSONParser implements DocParser {

    @Resource
    private IKnowledgeRawSegmentRepository knowledgeRawSegmentRepository;

    @Override
    public void chunk(KnowledgeDocumentModel documentDto, UserContext userContext) {
        try (InputStream inputStream = new URL(documentDto.getDocUrl()).openStream()) {
            String jsonContent = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            Object parsed = JSON.parse(jsonContent);

            JSONArray jsonArray;
            if (parsed instanceof JSONArray array) {
                jsonArray = array;
            } else if (parsed instanceof JSONObject obj) {
                // 单个 JSON 对象，包装成数组处理
                jsonArray = new JSONArray();
                jsonArray.add(obj);
            } else {
                log.error("JSON must be array or object, got: {}", parsed != null ? parsed.getClass().getName() : "null");
                throw KnowledgeException.build(BizExceptionCodeEnum.knowledgeJsonParseUnsupported);
            }

            for (int i = 0; i < jsonArray.size(); i++) {
                JSONObject jsonObject = jsonArray.getJSONObject(i);
                Map<String, String> filteredMap = new HashMap<>();
                for (String key : jsonObject.keySet()) {
                    Object value = jsonObject.get(key);
                    filteredMap.put(key, value != null ? value.toString() : "");
                }

                KnowledgeRawSegmentModel segment = new KnowledgeRawSegmentModel();
                segment.setDocId(documentDto.getId());
                segment.setKbId(documentDto.getKbId());
                segment.setSpaceId(documentDto.getSpaceId());
                segment.setRawTxt(JSON.toJSONString(filteredMap));
                segment.setSortIndex(i);

                if (segment.getRawTxt().length() > Constants.SEGMENT_MIN_WORDS / 2) {
                    this.knowledgeRawSegmentRepository.addInfo(segment, userContext);
                }
            }
        } catch (KnowledgeException e) {
            // 业务异常直接抛出，避免重复包装
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse document", e);
            throw KnowledgeException.build(BizExceptionCodeEnum.knowledgeDocumentParseFailed);
        }
    }

    @Override
    public Boolean isSupport(Integer dataType, String docUrl) {
        // JSON 文件统一由 TikaParser 处理
        return false;
    }
}
