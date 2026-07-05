package com.xspaceagi.knowledge.domain.docparser;

import com.xspaceagi.knowledge.sdk.enums.SegmentEnum;
import com.xspaceagi.knowledge.core.spec.utils.Commons;
import com.xspaceagi.knowledge.core.spec.utils.Constants;
import com.xspaceagi.knowledge.sdk.vo.SegmentConfigModel;
import com.xspaceagi.knowledge.domain.model.KnowledgeRawSegmentModel;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeRawSegmentRepository;
import com.xspaceagi.system.spec.common.UserContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
@Service
public class FileParseService {

    @Resource
    private IKnowledgeRawSegmentRepository knowledgeRawSegmentRepository;

    public void parseRawTxt(FileParseRequest fileParseRequest,
                            UserContext userContext) {
        Long kbId = fileParseRequest.getKbId();
        Long docId = fileParseRequest.getDocId();
        Long spaceId = fileParseRequest.getSpaceId();
        String content = fileParseRequest.getContent();
        SegmentConfigModel segmentConfig = fileParseRequest.getSegmentConfig();


        List<String> segments = null;

        int words = segmentConfig.getWords() == null ? 800 : segmentConfig.getWords();
        int overlaps = segmentConfig.getOverlaps() == null ? 10 : segmentConfig.getOverlaps();

        if (segmentConfig.getSegment().equals(SegmentEnum.WORDS)) {
            List<String> contents = new ArrayList<>(1);
            contents.add(content);
            segments = Commons.segmentStrings(contents, words, overlaps);
        } else if (segmentConfig.getSegment().equals(SegmentEnum.DELIMITER)) {
            var quote = Pattern.quote(segmentConfig.getDelimiter());
            List<String> afterSplits = Arrays.stream(content.split(quote))
                    .toList();

            segments = Commons.segmentStrings(afterSplits, words, overlaps);
        }
        if (segments == null) {
            return;
        }
        boolean isTrim = segmentConfig.getIsTrim();
        int sortIndex = 0;
        for (String s : segments) {
            if (StringUtils.isBlank(s)) {
                //分段出来的内容,是空白,无意义
                continue;
            }
            //判断非空的分段内容长度,如果长度小于等于 SEGMENT_MIN_WORDS (比如:10),不处理
            var strLen = s.length();
            if (strLen < Constants.SEGMENT_MIN_WORDS) {
                log.info("Segment length <= {}, skip, segment: {}", Constants.SEGMENT_MIN_WORDS, s);
                continue;
            }
            KnowledgeRawSegmentModel segment = new KnowledgeRawSegmentModel();
            String cleaned = isTrim ? s.replaceAll("\\s+", " ") : s;
            segment.setRawTxt(cleaned);
            segment.setDocId(docId);
            segment.setKbId(kbId);
            segment.setSortIndex(sortIndex);
            segment.setSpaceId(spaceId);

            this.knowledgeRawSegmentRepository.addInfo(segment, userContext);
            sortIndex++;
        }
    }


}
