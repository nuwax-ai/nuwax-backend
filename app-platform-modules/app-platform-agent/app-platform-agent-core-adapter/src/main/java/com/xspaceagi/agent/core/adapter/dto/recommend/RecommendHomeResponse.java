package com.xspaceagi.agent.core.adapter.dto.recommend;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendHomeResponse {

    private Map<String, List<TargetRecommendResponse>> recHome;

    private Map<String, List<TargetRecommendResponse>> recChatBoxNav;
}
