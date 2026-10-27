package com.dbappsecurity.prd2api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文件元信息
 */
@Data
@TableName("file_meta")
public class FileMeta {

    @TableId(value = "file_meta_id", type = IdType.ASSIGN_UUID)
    private String fileMetaId;

    private String tenantId;

    private String fileName;

    private String fileSuffix;

    private String fileStorePath;

    private String fileMd5;

    private Long fileSize;

    private LocalDateTime createTime;

    @TableLogic
    private Integer deleted;
}
