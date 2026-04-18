package com.xspaceagi.knowledge.domain.vectordb.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.xspaceagi.agent.core.adapter.application.ModelApplicationService;
import com.xspaceagi.agent.core.adapter.dto.config.ModelConfigDto;
import com.xspaceagi.knowledge.core.spec.KnowledgeConstants;
import com.xspaceagi.knowledge.core.spec.utils.Commons;
import com.xspaceagi.knowledge.core.spec.utils.Constants;
import com.xspaceagi.knowledge.domain.dto.qa.QAEmbeddingDto;
import com.xspaceagi.knowledge.domain.dto.qa.QAQueryEmbeddingDto;
import com.xspaceagi.knowledge.domain.dto.qa.QAResDto;
import com.xspaceagi.knowledge.domain.model.KnowledgeDocumentModel;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeConfigRepository;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeDocumentRepository;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeQaSegmentRepository;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeRawSegmentRepository;
import com.xspaceagi.knowledge.domain.vectordb.VectorDBService;
import com.xspaceagi.knowledge.sdk.enums.KnowledgePubStatusEnum;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.KnowledgeException;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.*;
import io.milvus.v2.service.index.request.CreateIndexReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MilvusDomainService implements VectorDBService {

        @Lazy
        @Resource
        private MilvusClientV2 client;

        @Resource
        private IKnowledgeConfigRepository knowledgeConfigRepository;

        @Resource
        private IKnowledgeDocumentRepository knowledgeDocumentRepository;

        @Resource
        private IKnowledgeQaSegmentRepository knowledgeQaSegmentRepository;

        @Resource
        private IKnowledgeRawSegmentRepository knowledgeRawSegmentRepository;

        @Resource
        private ModelApplicationService modelApplicationService;

        @Lazy
        @Resource
        private VectorDBService self;

        @Override
        public boolean checkCollectionExists(String collectionName) {
                HasCollectionReq param = HasCollectionReq.builder()
                                .collectionName(collectionName)
                                .build();
                return client.hasCollection(param);
        }

        @Override
        public boolean checkCollectionAndDocExists(String collectionName, Long docId) {

                QueryReq param = QueryReq.builder()
                                .collectionName(collectionName)
                                .filter(Constants.VECTOR_DB_DOC_ID + " in [" + docId + "]")
                                .build();

                var existInfo = client.query(param);
                return Objects.nonNull(existInfo);
        }

        @Override
        public boolean checkCollectionAndQaExists(String collectionName, Long qaId) {

                QueryReq param = QueryReq.builder()
                                .collectionName(collectionName)
                                .filter(Constants.VECTOR_DB_QA_ID + " in [" + qaId + "]")
                                .build();

                var existInfo = client.query(param);
                return Objects.nonNull(existInfo);
        }

        @Override
        public void initAndCheckCollection(Long kbId, Long embeddingModelId) {
                String collectionName = Commons.collectionName(kbId);

                var existFlag = checkCollectionExists(collectionName);
                if (!existFlag) {

                        ModelConfigDto embedModel;
                        if (Objects.nonNull(embeddingModelId)) {
                                embedModel = this.modelApplicationService.queryModelConfigById(embeddingModelId);
                        } else {
                                // 获取默认生成向量的模型
                                embedModel = this.modelApplicationService.getDefaultEmbedModel();
                        }

                        CreateCollectionReq.CollectionSchema schema = client.createSchema();
                        schema.addField(AddFieldReq.builder().fieldName(Constants.VECTOR_DB_QA_ID)
                                        .dataType(DataType.Int64).isPrimaryKey(true).build());
                        schema.addField(AddFieldReq.builder().fieldName(Constants.VECTOR_DB_DOC_ID)
                                        .dataType(DataType.Int64).build());
                        schema.addField(AddFieldReq.builder().fieldName(Constants.VECTOR_DB_QUESTION)
                                        .dataType(DataType.VarChar).build());
                        schema.addField(AddFieldReq.builder().fieldName(Constants.VECTOR_DB_ANSWER)
                                        .dataType(DataType.VarChar).build());
                        schema.addField(AddFieldReq.builder().fieldName(Constants.VECTOR_DB_RAW_TEXT)
                                        .dataType(DataType.VarChar).build());
                        schema.addField(AddFieldReq.builder().fieldName(Constants.VECTOR_DB_EMBEDDINGS)
                                        .dataType(DataType.FloatVector).dimension(embedModel.getDimension())
                                        .build());

                        CreateCollectionReq param = CreateCollectionReq.builder()
                                        .collectionName(collectionName)
                                        .collectionSchema(schema)
                                        .build();
                        client.createCollection(param);

                        // 创建索引；TODO 自定义参数
                        Map<String, Object> extraParams = new HashMap<>();
                        extraParams.put("nlist", 128);

                        IndexParam indexParamVec = IndexParam.builder()
                                        .fieldName(Constants.VECTOR_DB_EMBEDDINGS)
                                        .indexName(Constants.VECTOR_DB_EMBEDDINGS_INDEX_VECTOR)
                                        .indexType(IndexParam.IndexType.IVF_FLAT)
                                        .metricType(IndexParam.MetricType.COSINE)
                                        .extraParams(extraParams)
                                        .build();

                        IndexParam indexForID = IndexParam.builder()
                                        .fieldName(Constants.VECTOR_DB_QA_ID)
                                        .indexName(Constants.VECTOR_DB_EMBEDDINGS_INDEX_ID)
                                        .indexType(IndexParam.IndexType.INVERTED)
                                        .build();

                        IndexParam indexForDocID = IndexParam.builder()
                                        .fieldName(Constants.VECTOR_DB_DOC_ID)
                                        .indexName(Constants.VECTOR_DB_EMBEDDINGS_INDEX_DOC)
                                        .indexType(IndexParam.IndexType.INVERTED)
                                        .build();

                        List<IndexParam> indexParams = new ArrayList<>();
                        indexParams.add(indexParamVec);
                        indexParams.add(indexForID);
                        indexParams.add(indexForDocID);

                        CreateIndexReq createIndexReq = CreateIndexReq.builder()
                                        .collectionName(collectionName)
                                        .indexParams(indexParams)
                                        .build();
                        client.createIndex(createIndexReq);
                }
        }

        @Override
        public void removeDoc(Long kbId,Long docId) {


                        var collectionName = Commons.collectionName(kbId);
                        // 检查集合是否存在
                        var existFlag = checkCollectionExists(collectionName);
                        if (!existFlag) {
                                return;
                        }

                        // 加载集合到内存,先判断有无加载
                        var loadStateReq = GetLoadStateReq.builder()
                                        .collectionName(collectionName)
                                        .build();
                        var loadState = client.getLoadState(loadStateReq);
                        if (Boolean.FALSE.equals(loadState)) {
                                LoadCollectionReq loadCollectionReq = LoadCollectionReq.builder()
                                                .collectionName(collectionName)
                                                .build();
                                client.loadCollection(loadCollectionReq);
                        }

                        // 删除文档
                        DeleteReq deleteReq = DeleteReq.builder()
                                        .collectionName(Commons.collectionName(kbId))
                                        .filter(Constants.VECTOR_DB_DOC_ID + " in [" + docId + "]")
                                        .build();
                        client.delete(deleteReq);
        }

        @Override
        public void removeQa(List<Long> qaIds, Long kbId) {

                if (Objects.nonNull(kbId) && !CollectionUtils.isEmpty(qaIds)) {
                        String collectionName = Commons.collectionName(kbId);

                        // 检查集合是否存在
                        if (!checkCollectionExists(collectionName)) {
                                log.warn("Collection not found, skip delete: collectionName={}, kbId={}", collectionName, kbId);
                                return;
                        }

                        String qaIdsStr = qaIds.stream()
                                        .map(String::valueOf)
                                        .collect(Collectors.joining(","));
                        DeleteReq deleteReq = DeleteReq.builder()
                                        .collectionName(collectionName)
                                        .filter(Constants.VECTOR_DB_QA_ID + " in [" + qaIdsStr + "]")
                                        .build();
                        client.delete(deleteReq);
                }

        }

        @Override
        public void addEmbeddingQaForBatch(List<QAEmbeddingDto> qaEmbeddingDtos, boolean isUpdate) {

                List<JsonObject> data = qaEmbeddingDtos.stream().map(qaEmbeddingDto -> {
                        JsonObject jsonObject = new JsonObject();
                        jsonObject.addProperty(Constants.VECTOR_DB_QA_ID,
                                        qaEmbeddingDto.getQaId());
                        jsonObject.addProperty(Constants.VECTOR_DB_DOC_ID,
                                        qaEmbeddingDto.getDocId());
                        jsonObject.addProperty(Constants.VECTOR_DB_QUESTION,
                                        qaEmbeddingDto.getQuestion());
                        jsonObject.addProperty(Constants.VECTOR_DB_ANSWER,
                                        qaEmbeddingDto.getAnswer());
                        jsonObject.addProperty(Constants.VECTOR_DB_RAW_TEXT,
                                        qaEmbeddingDto.getRawTxt());

                        List<Float> floatEmbeddings = qaEmbeddingDto.getEmbeddings().stream()
                                        .map(BigDecimal::floatValue)
                                        .toList();
                        JsonArray jsonArray = new JsonArray();
                        floatEmbeddings.forEach(jsonArray::add);
                        jsonObject.add(Constants.VECTOR_DB_EMBEDDINGS, jsonArray);

                        return jsonObject;
                })
                                .toList();

                var kbId = qaEmbeddingDtos.stream()
                                .findFirst()
                                .map(QAEmbeddingDto::getKbId)
                                .orElse(null);
                if (Objects.isNull(kbId)) {
                        log.warn("kbId empty [addEmbeddingQaForBatch]");
                        throw KnowledgeException.build(BizExceptionCodeEnum.fieldRequiredButEmpty, "知识库ID");
                }
                String collectionName = Commons.collectionName(kbId);

                // 确保集合存在，如果不存在则初始化
                if (!checkCollectionExists(collectionName)) {
                        log.info("Collection missing, init: collectionName={}, kbId={}", collectionName, kbId);
                        // 从知识库配置中获取嵌入模型ID
                        var kbConfig = this.knowledgeConfigRepository.queryOneInfoById(kbId);
                        Long embeddingModelId = Objects.nonNull(kbConfig) ? kbConfig.getEmbeddingModelId() : null;
                        this.self.initAndCheckCollection(kbId, embeddingModelId);
                }

                var qaIds = qaEmbeddingDtos.stream()
                                .map(QAEmbeddingDto::getQaId)
                                .toList();

                // 删除问答对应的旧数据
                this.self.removeQa(qaIds, kbId);

                // 新增
                InsertReq insertReq = InsertReq.builder()
                                .collectionName(collectionName)
                                .data(data)
                                .build();
                client.insert(insertReq);

        }

        @Override
        public void addEmbeddingQa(QAEmbeddingDto qaEmbeddingDto,
                        boolean isUpdate) {
                ArrayList<QAEmbeddingDto> qaEmbeddingDtos = new ArrayList<>();
                qaEmbeddingDtos.add(qaEmbeddingDto);
                this.self.addEmbeddingQaForBatch(qaEmbeddingDtos, isUpdate);
        }

        @Override
        public List<QAResDto> searchEmbedding(QAQueryEmbeddingDto qaQueryDto) {
                List<QAResDto> res = new ArrayList<>();

                var kbId = qaQueryDto.getKbId();
                // 得到发布的文档列表,可以根据
                // 发布状态(KnowledgePubStatusEnum)进行查询限制,如果KnowledgePubStatusEnum为空,则不限制条件
                KnowledgePubStatusEnum knowledgePubStatusEnum = null;
                var documentIdList = this.knowledgeDocumentRepository.queryDocIdsByConfigId(kbId,
                                knowledgePubStatusEnum);
                // 增加 docId = 0的情况,因为手动添加的docId 都是0
                documentIdList.add(KnowledgeConstants.MANUAL_ADD_DOC_ID);

                // load the collection
                GetLoadStateReq loadStateReq = GetLoadStateReq.builder()
                                .collectionName(Commons.collectionName(qaQueryDto.getKbId()))
                                .build();
                if (Boolean.FALSE.equals(client.getLoadState(loadStateReq))) {
                        LoadCollectionReq loadCollectionReq = LoadCollectionReq.builder()
                                        .collectionName(Commons.collectionName(qaQueryDto.getKbId()))
                                        .build();
                        client.loadCollection(loadCollectionReq);
                }

                var docIdStr = documentIdList.stream().map(String::valueOf).collect(Collectors.joining(","));
                String docListExpr = Constants.VECTOR_DB_DOC_ID + " in [" + docIdStr + "]";

                List<BaseVector> queryVectors = Collections.singletonList(new FloatVec(qaQueryDto.getEmbedding()
                                .stream()
                                .map(BigDecimal::floatValue)
                                .toList()));
                SearchReq searchRes = SearchReq.builder()
                                .collectionName(Commons.collectionName(qaQueryDto.getKbId()))
                                .data(queryVectors)
                                .filter(docListExpr)
                                .outputFields(Arrays.asList(Constants.VECTOR_DB_QA_ID, Constants.VECTOR_DB_QUESTION,
                                                Constants.VECTOR_DB_DOC_ID,
                                                Constants.VECTOR_DB_ANSWER, Constants.VECTOR_DB_RAW_TEXT))
                                .topK(qaQueryDto.getTopK())
                                .build();
                List<SearchResp.SearchResult> resultList = client.search(searchRes).getSearchResults().get(0);

                for (SearchResp.SearchResult searchResult : resultList) {
                        QAResDto qaResDto = new QAResDto();
                        qaResDto.setKbId(qaQueryDto.getKbId());
                        qaResDto.setQaId((Long) searchResult.getId());
                        qaResDto.setDocId((Long)searchResult.getEntity().get(Constants.VECTOR_DB_DOC_ID));
                        qaResDto.setScore(searchResult.getScore());
                        qaResDto.setQuestion((String) searchResult.getEntity().get(Constants.VECTOR_DB_QUESTION));
                        qaResDto.setAnswer((String) searchResult.getEntity().get(Constants.VECTOR_DB_ANSWER));
                        qaResDto.setRawTxt((String) searchResult.getEntity().get(Constants.VECTOR_DB_RAW_TEXT));
                        res.add(qaResDto);
                }
                return res;
        }

        @Override
        public void removeEmbeddingQA(Long qaID, Long kbId) {
                String collectionName = Commons.collectionName(kbId);

                // 检查集合是否存在
                if (!checkCollectionExists(collectionName)) {
                        log.warn("Collection not found, skip delete: collectionName={}, kbId={}, qaId={}", collectionName, kbId, qaID);
                        return;
                }

                DeleteReq deleteReq = DeleteReq.builder()
                                .collectionName(collectionName)
                                .ids(Collections.singletonList(qaID))
                                .build();
                client.delete(deleteReq);
        }

        @Override
        public void deleteCollection(Long kbId) {
                String collectionName = Commons.collectionName(kbId);

                // 检查集合是否存在
                if (!checkCollectionExists(collectionName)) {
                        log.warn("Collection not found, skip delete: collectionName={}, kbId={}", collectionName, kbId);
                        return;
                }

                DropCollectionReq dropCollectionReq = DropCollectionReq.builder()
                                .collectionName(collectionName)
                                .build();
                client.dropCollection(dropCollectionReq);
        }

        @Override
        public void removeEmbeddingQaIds(Long docId, List<Long> qaIds) {
                KnowledgeDocumentModel document = this.knowledgeDocumentRepository.queryOneInfoById(docId);
                String collectionName = Commons.collectionName(document.getKbId());

                // 检查集合是否存在
                if (!checkCollectionExists(collectionName)) {
                        log.warn("Collection not found, skip delete: collectionName={}, kbId={}, docId={}",
                                collectionName, document.getKbId(), docId);
                        return;
                }

                DeleteReq deleteReq = DeleteReq.builder()
                                .collectionName(collectionName)
                                .ids(new ArrayList<>(qaIds))
                                .build();
                client.delete(deleteReq);
        }
}
