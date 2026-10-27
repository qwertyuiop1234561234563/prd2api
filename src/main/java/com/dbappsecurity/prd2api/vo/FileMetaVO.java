package com.dbappsecurity.prd2api.vo;

import com.dbappsecurity.prd2api.entity.FileMeta;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文件元信息视图
 */
@Data
@Schema(description = "文件元信息视图")
public class FileMetaVO {

    @Schema(description = "文件元数据ID")
    private String fileMetaId;

    @Schema(description = "租户ID")
    private String tenantId;

    @Schema(description = "原始文件名")
    private String fileName;

    @Schema(description = "文件后缀")
    private String fileSuffix;

    @Schema(description = "文件大小字节")
    private Long fileSize;

    @Schema(description = "文件md5")
    private String fileMd5;

    @Schema(description = "上传时间")
    private LocalDateTime createTime;

    public static FileMetaVO from(FileMeta meta) {
        if (meta == null) {
            return null;
        }
        FileMetaVO vo = new FileMetaVO();
        vo.setFileMetaId(meta.getFileMetaId());
        vo.setTenantId(meta.getTenantId());
        vo.setFileName(meta.getFileName());
        vo.setFileSuffix(meta.getFileSuffix());
        vo.setFileSize(meta.getFileSize());
        vo.setFileMd5(meta.getFileMd5());
        vo.setCreateTime(meta.getCreateTime());
        return vo;
    }
}
