package com.dbappsecurity.prd2api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理员开通 VIP 入参
 */
@Data
@Schema(description = "管理员开通 VIP 入参")
public class VipGrantDTO {

    @NotBlank(message = "userId 不能为空")
    @Schema(description = "用户ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String userId;

    @NotBlank(message = "planCode 不能为空")
    @Schema(description = "套餐编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "BASIC")
    private String planCode;

    @Size(max = 256, message = "备注最长 256 字符")
    @Schema(description = "备注")
    private String remark;
}
