package com.xspaceagi.eco.market.web.controller.dto.req;

import com.xspaceagi.eco.market.domain.model.EcoMarketClientConfigModel;
import com.xspaceagi.system.spec.common.UserContext;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 客户端配置更新草稿请求DTO
 */
@Getter
@Setter
@Schema(description = "客户端配置更新草稿请求DTO")
public class ClientConfigUpdateDraftReqDTO extends ClientConfigSaveReqDTO {

    /**
     * 配置唯一标识
     */
    @NotBlank(message = "Configuration UID is required")
    @Schema(description = "配置唯一标识", requiredMode = RequiredMode.REQUIRED)
    private String uid;

    /**
     * 将DTO转换为模型对象
     * 
     * @param reqDTO 请求DTO
     * @param userContext 用户上下文
     * @return 模型对象
     */
    public static EcoMarketClientConfigModel convert2Dto(ClientConfigUpdateDraftReqDTO reqDTO, UserContext userContext) {
        // 使用父类的转换方法
        EcoMarketClientConfigModel model = ClientConfigSaveReqDTO.convert2Dto(reqDTO, userContext);
        // 设置uid
        model.setUid(reqDTO.getUid());
        return model;
    }
}