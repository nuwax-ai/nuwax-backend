package com.xspaceagi.system.infra.dao.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xspaceagi.system.infra.dao.entity.OpenApiDefinition;
import com.xspaceagi.system.infra.dao.service.OpenApiDefinitionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@Service
public class OpenApiDefinitionServiceImpl implements OpenApiDefinitionService {

    private static final Logger logger = LoggerFactory.getLogger(OpenApiDefinitionServiceImpl.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String JSON_PATH = "open-api-definition.json";

    private static final List<OpenApiDefinition> openApiDefinitions;

    static {
        try {
            // 从 classpath 读取 JSON 文件
            ClassPathResource resource = new ClassPathResource(JSON_PATH);
            InputStream inputStream = resource.getInputStream();

            // 使用 Jackson 将 JSON 转换为 Java 对象
            openApiDefinitions = objectMapper.readValue(
                inputStream,
                new TypeReference<List<OpenApiDefinition>>() {}
            );

            logger.info("成功加载 {} 个 OpenAPI 定义组", openApiDefinitions.size());

            // 输出加载的 API 定义信息
            for (OpenApiDefinition definition : openApiDefinitions) {
                int apiCount = definition.getApiList() != null ? definition.getApiList().size() : 0;
                logger.info("  - [{}] {} (包含 {} 个 API)", definition.getKey(), definition.getName(), apiCount);
            }

        } catch (IOException e) {
            logger.error("加载 OpenAPI 定义文件失败：{}", JSON_PATH, e);
            throw new RuntimeException("加载 OpenAPI 定义文件失败：" + JSON_PATH, e);
        }
    }

    @Override
    public List<OpenApiDefinition> queryAll() {
        return openApiDefinitions;
    }
}
