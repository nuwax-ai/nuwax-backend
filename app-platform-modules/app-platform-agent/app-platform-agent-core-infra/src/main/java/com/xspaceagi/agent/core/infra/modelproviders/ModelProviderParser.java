package com.xspaceagi.agent.core.infra.modelproviders;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.agent.core.infra.modelproviders.vo.ModelProviderVo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 模型供应商配置解析器
 * 从 classpath:providers/*.json 加载并解析供应商配置
 */
@Slf4j
public class ModelProviderParser {

    private static final String PROVIDERS_LOCATION = "classpath:providers/*.json";

    private static List<ModelProviderVo> modelProviderVos;

    private static Map<String, Map<String, ModelProviderVo.ModelInfo>> modelProviderMap;

    private ModelProviderParser() {
    }

    /**
     * 加载所有供应商配置
     *
     * @return 供应商配置列表
     */
    public static List<ModelProviderVo> loadAll() {
        if (modelProviderVos != null) {
            return modelProviderVos;
        }
        List<ModelProviderVo> result = new ArrayList<>();

        try {
            ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(PROVIDERS_LOCATION);

            for (Resource resource : resources) {
                try {
                    ModelProviderVo vo = parseResource(resource);
                    if (vo != null) {
                        result.add(vo);
                    }
                } catch (Exception e) {
                    log.warn("解析供应商配置失败: {}，已跳过", resource.getFilename(), e);
                }
            }

            log.info("成功加载 {} 个供应商配置", result.size());
        } catch (IOException e) {
            log.error("读取供应商配置文件失败", e);
        }
        //result按照name排序
        result = result.stream().sorted((o1, o2) -> o1.getName().compareTo(o2.getName())).collect(Collectors.toList());
        result.forEach(vo -> {
            if (vo.getModels() != null) {
                vo.setModels(vo.getModels().stream()
                        .sorted(Comparator.comparing(ModelProviderVo.ModelInfo::getReleaseDate,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                        .collect(Collectors.toList()));
            }
        });
        ModelProviderVo defaultProvider = ModelProviderVo.builder()
                .pid("custom")
                .name("Customization")
                .icon("http://s3.nuwax.com:9443/nuwax-public/providers/custom.png")
                .models(new ArrayList<>())
                .build();
        result.add(defaultProvider);
        modelProviderVos = result;
        modelProviderMap = modelProviderVos.stream().collect(Collectors.toMap(
                ModelProviderVo::getPid,
                v -> v.getModels() == null
                        ? Collections.emptyMap()
                        : v.getModels().stream().collect(Collectors.toMap(
                        ModelProviderVo.ModelInfo::getId,
                        Function.identity(),
                        (a, b) -> a))));
        return result;
    }

    public static Map<String, Map<String, ModelProviderVo.ModelInfo>> getModelProviderMap() {
        if (modelProviderMap == null) {
            loadAll();
        }
        return modelProviderMap;
    }

    /**
     * 解析单个 JSON 资源文件
     */
    private static ModelProviderVo parseResource(Resource resource) throws IOException {
        String filename = resource.getFilename();
        if (filename == null || !filename.endsWith(".json")) {
            return null;
        }

        String pid = filename.replace(".json", "");

        String json;
        try (InputStream is = resource.getInputStream()) {
            json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        JSONObject root = JSON.parseObject(json);

        // 解析 provider 对象
        JSONObject providerObj = root.getJSONObject("provider");
        if (providerObj == null) {
            log.warn("{} 缺少 provider 字段，已跳过", filename);
            return null;
        }

        ModelProviderVo vo = new ModelProviderVo();
        vo.setPid(pid);
        vo.setName(providerObj.getString("name"));
        vo.setIcon(providerObj.getString("icon"));
        vo.setDoc(providerObj.getString("doc"));

        // 解析 api -> ApiInfo
        JSONObject apiObj = providerObj.getJSONObject("api");
        if (apiObj != null) {
            ModelProviderVo.ApiInfo apiInfo = new ModelProviderVo.ApiInfo();
            apiInfo.setOpenAI(apiObj.getString("OpenAI"));
            apiInfo.setAnthropic(apiObj.getString("Anthropic"));
            vo.setApiInfo(apiInfo);
        }

        // 解析 models
        List<ModelProviderVo.ModelInfo> modelList = parseModels(root.getJSONArray("models"));
        if (modelList != null) {
            modelList.forEach(modelInfo -> {
                if (StringUtils.isBlank(modelInfo.getName())) {
                    modelInfo.setName(modelInfo.getId());
                }
            });
            vo.setModels(modelList);
        }
        return vo;
    }

    /**
     * 解析模型列表
     */
    private static List<ModelProviderVo.ModelInfo> parseModels(
            com.alibaba.fastjson2.JSONArray modelsArray) {

        if (modelsArray == null || modelsArray.isEmpty()) {
            return Collections.emptyList();
        }

        return modelsArray.stream()
                .map(obj -> parseModel((JSONObject) obj))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 解析单个模型
     */
    private static ModelProviderVo.ModelInfo parseModel(JSONObject modelObj) {
        if (modelObj == null) return null;

        ModelProviderVo.ModelInfo model = new ModelProviderVo.ModelInfo();
        model.setId(modelObj.getString("id"));
        model.setName(modelObj.getString("name"));

        // 布尔属性
        model.setAttachment(modelObj.getBoolean("attachment"));
        model.setReasoning(modelObj.getBoolean("reasoning"));
        model.setTemperature(modelObj.getBoolean("temperature"));
        model.setToolCall(modelObj.getBoolean("tool_call"));
        model.setStructuredOutput(modelObj.getBoolean("structured_output"));

        model.setReleaseDate(modelObj.getString("release_date"));
        model.setKnowledge(modelObj.getString("knowledge"));

        // 解析 interleaved
        JSONObject interleavedObj = modelObj.getJSONObject("interleaved");
        if (interleavedObj != null) {
            ModelProviderVo.InterleavedInfo interleaved = new ModelProviderVo.InterleavedInfo();
            interleaved.setField(interleavedObj.getString("field"));
            model.setInterleaved(interleaved);
        }

        // 解析 limit
        JSONObject limitObj = modelObj.getJSONObject("limit");
        if (limitObj != null) {
            ModelProviderVo.ModelLimit limit = new ModelProviderVo.ModelLimit();
            limit.setContext(limitObj.getInteger("context"));
            limit.setOutput(limitObj.getInteger("output"));
            model.setLimit(limit);
        }

        // 解析 modalities
        JSONObject modalitiesObj = modelObj.getJSONObject("modalities");
        if (modalitiesObj != null) {
            ModelProviderVo.ModelModalities modalities = new ModelProviderVo.ModelModalities();
            modalities.setInput(modalitiesObj.getList("input", String.class));
            modalities.setOutput(modalitiesObj.getList("output", String.class));
            model.setModalities(modalities);
        }

        return model;
    }
}