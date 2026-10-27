package com.dbappsecurity.prd2api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 报告缓存：相同 文件md5 + 模型 + 分块策略 直接返回历史结果，节省 token
 */
@Data
@TableName("prd_task_cache")
public class PrdTaskCache {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String tenantId;

    private String fileMd5;

    private String modelCode;

    private String chunkStrategy;

    private String cacheResultMarkdown;

    private LocalDateTime expireTime;

    private LocalDateTime createTime;
}
