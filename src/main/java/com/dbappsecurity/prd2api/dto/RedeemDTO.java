package com.dbappsecurity.prd2api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 兑换码兑换入参
 */
@Data
@Schema(description = "兑换码兑换入参")
public class RedeemDTO {

    @NotBlank(message = "兑换码不能为空")
    @Size(max = 32, message = "兑换码长度不能超过 32")
    @Schema(description = "兑换码", requiredMode = Schema.RequiredMode.REQUIRED)
    private String code;
}
