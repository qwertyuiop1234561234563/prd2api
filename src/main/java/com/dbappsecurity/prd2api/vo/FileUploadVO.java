package com.dbappsecurity.prd2api.vo;

import com.dbappsecurity.prd2api.entity.FileMeta;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 文件上传结果
 */
@Data
@Schema(description = "文件上传结果")
public class FileUploadVO {

    @Schema(description = "文件元数据ID，创建任务时使用")
    private String fileMetaId;

    @Schema(description = "原始文件名")
    private String fileName;

    @Schema(description = "文件后缀")
    private String fileSuffix;

    @Schema(description = "文件大小（字节）")
    private Long fileSize;

    @Schema(description = "文件 md5")
    private String fileMd5;

    @Schema(description = "是否命中 md5 去重，true 表示复用了已有文件")
    private Boolean duplicated;

    @Schema(description = "相似文档ID，非空表示检测到内容高度相似的已有文档")
    private String similarFileMetaId;

    @Schema(description = "与相似文档的相似度，0~1")
    private Double similarity;

    public static FileUploadVO from(FileMeta meta, boolean duplicated) {
        FileUploadVO vo = new FileUploadVO();
        vo.setFileMetaId(meta.getFileMetaId());
        vo.setFileName(meta.getFileName());
        vo.setFileSuffix(meta.getFileSuffix());
        vo.setFileSize(meta.getFileSize());
        vo.setFileMd5(meta.getFileMd5());
        vo.setDuplicated(duplicated);
        return vo;
    }
}
