package com.xspaceagi.pay.application.service.support;

import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.UserApplicationService;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PayDeveloperAccountUserNameSupport {

    public static Map<Long, String> loadUserNameMap(UserApplicationService userApplicationService, Collection<Long> userIds) {
        if (userApplicationService == null || userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<Long> distinct = userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> out = new HashMap<>();
        for (UserDto u : userApplicationService.queryUserListByIds(distinct)) {
            if (u.getId() != null) {
                out.putIfAbsent(u.getId(), u.getUserName());
            }
        }
        return out;
    }
}
