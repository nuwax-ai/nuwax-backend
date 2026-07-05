package com.xspaceagi.agent.core.adapter.application;

import com.xspaceagi.agent.core.adapter.dto.recommend.RecommendHomeResponse;
import com.xspaceagi.agent.core.adapter.dto.recommend.TargetRecommendPageRequest;
import com.xspaceagi.agent.core.adapter.dto.recommend.TargetRecommendResponse;
import com.xspaceagi.agent.core.adapter.dto.recommend.TargetRecommendSaveRequest;

import java.util.List;

public interface RecommendApplicationService {

    void save(TargetRecommendSaveRequest request);

    void update(TargetRecommendSaveRequest request);

    void delete(Long id);

    TargetRecommendResponse getById(Long id);

    List<TargetRecommendResponse> page(TargetRecommendPageRequest request);

    List<TargetRecommendResponse> list(String recType, String targetType);

    void updateSort(Long id, Integer sort);

    RecommendHomeResponse getRecommendations();
}
