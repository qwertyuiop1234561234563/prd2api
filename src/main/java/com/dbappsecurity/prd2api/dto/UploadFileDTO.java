package com.dbappsecurity.prd2api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 创建解析任务入参
 */
@Data
@Schema(description = "创建解析任务入参")
public class UploadFileDTO {

    @NotBlank(message = "fileMetaId 不能为空")
    @Schema(description = "文件元数据ID（上传接口返回）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String fileMetaId;

    @Schema(description = "模型编码：default / deepseek-chat / deepseek-reasoner / qwen-plus", defaultValue = "default")
    private String modelCode = "default";

    @Schema(description = "分块策略：fixed / semantic，缺省使用服务端配置", defaultValue = "semantic")
    private String chunkStrategy;
}
