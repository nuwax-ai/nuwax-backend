package com.xspaceagi.knowledge.core.spec.utils;

public class Constants {

    public static final Integer SEGMENT_MIN_WORDS = 10;

    /**
     * Tika 文本抽取缓冲上限(字符数,约对应40-60MB文本):
     * 防止构造异常的文档解出GB级文本撑爆堆;超限时Tika抛WriteLimitReached,走既有解析失败/兜底流程
     */
    public static final int TIKA_TEXT_WRITE_LIMIT = 20_000_000;

    // 一般一个中文字符约0.6个token，而一个英文字符约0.3个token，这里采用保守估计
    public static final Float TOKENS_PER_CHAR = 3.0f;

    /**
     * 向量化(embedding)的批量大小:一次 embeddings 调用处理的问答条数。
     * 受 embedding 模型单次 input/token 上限制约,过大可能报错或截断;过小则 embedding 调用频繁、慢。
     */
    public static final int BATCH_SIZE = 2;

    public static final String VECTOR_DB_NAME = "xspaceagi";
    public static final String VECTOR_DB_COLLECTION_PRE = "kb_";
    public static final String VECTOR_DB_QA_ID = "qa_id";
    public static final String VECTOR_DB_DOC_ID = "doc_id";
    public static final String VECTOR_DB_QUESTION = "question";
    public static final String VECTOR_DB_ANSWER = "answer";
    /**
     * qa问答对应的原始分段文本,可能没有
     */
    public static final String VECTOR_DB_RAW_TEXT = "raw_text";
    public static final String VECTOR_DB_EMBEDDINGS = "vector";
    public static final String VECTOR_DB_EMBEDDINGS_INDEX_VECTOR = "index_vector";
    public static final String VECTOR_DB_EMBEDDINGS_INDEX_ID = "index_id";
    public static final String VECTOR_DB_EMBEDDINGS_INDEX_DOC = "index_doc";

    public static final String QA_JSON_QUESTION = "question";
    public static final String QA_JSON_ANSWER = "answer";
}
