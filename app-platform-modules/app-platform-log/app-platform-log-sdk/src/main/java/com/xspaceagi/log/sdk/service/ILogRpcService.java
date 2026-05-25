package com.xspaceagi.log.sdk.service;

import com.xspaceagi.log.sdk.request.DocumentSearchRequest;
import com.xspaceagi.log.sdk.vo.LogDocument;
import com.xspaceagi.log.sdk.vo.SearchResult;

import java.util.List;

public interface ILogRpcService {

    void bulkIndex(List<LogDocument> list);

    void pushTraceLog(Object traceContext);

    void deleteLogDocument(String id);

    SearchResult search(DocumentSearchRequest documentSearchRequest);
}
