package com.xspaceagi.agent.core.spec.utils;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

@Slf4j
public class TikTokensUtil {

    private static final EncodingRegistry registry = Encodings.newLazyEncodingRegistry();

    public static int tikTokensCount(String text) {
        try {
            if (StringUtils.isBlank(text)) {
                return 0;
            }
            Encoding encoding = registry.getEncoding(EncodingType.CL100K_BASE);
            return encoding.countTokens(text);
        } catch (Exception e) {
            log.warn("Error calculating tikTokensCount: {}", e.getMessage());
            return 0;
        }
    }
}
