package com.dbappsecurity.prd2api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 任务追加追问入参
 */
@Data
@Schema(description = "任务追加追问入参")
public class AgentChatDTO {

    @NotBlank(message = "taskId 不能为空")
    @Schema(description = "任务ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String taskId;

    @NotBlank(message = "question 不能为空")
    @Size(max = 2000, message = "追问内容不能超过 2000 字")
    @Schema(description = "追问内容", requiredMode = Schema.RequiredMode.REQUIRED)
    private String question;
}
