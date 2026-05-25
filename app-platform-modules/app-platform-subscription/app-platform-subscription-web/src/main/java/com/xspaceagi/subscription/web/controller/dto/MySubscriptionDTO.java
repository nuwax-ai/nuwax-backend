package com.xspaceagi.subscription.web.controller.dto;

import com.xspaceagi.subscription.sdk.dto.UserSubscriptionDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
public class MySubscriptionDTO {

    @Schema(description = "当前订阅")
    private UserSubscriptionDTO currentSubscription;

    @Schema(description = "所有订阅")
    private List<UserSubscriptionDTO> subscriptions;
}
