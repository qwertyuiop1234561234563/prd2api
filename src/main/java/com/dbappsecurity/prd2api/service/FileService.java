package com.dbappsecurity.prd2api.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dbappsecurity.prd2api.dto.FileQueryDTO;
import com.dbappsecurity.prd2api.dto.UploadFileDTO;
import com.dbappsecurity.prd2api.vo.FileMetaVO;
import com.dbappsecurity.prd2api.vo.FileUploadVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件接入服务
 */
public interface FileService {

    /**
     * 上传文件：后缀 + 魔数双校验、md5 去重、本地/对象存储落盘、元信息入库
     */
    FileUploadVO upload(MultipartFile file);

    /**
     * 分页查询文件元信息（租户隔离）
     */
    Page<FileMetaVO> pageFiles(FileQueryDTO dto);

    /**
     * 解析文档文本（带 Redis 缓存），供 Agent 流水线使用
     */
    String loadText(com.dbappsecurity.prd2api.entity.FileMeta fileMeta);

    /**
     * 物理删除文件并逻辑删除元信息
     */
    void deleteFile(String fileMetaId);

    /**
     * 清理过期文件
     */
    int cleanExpired();

    /**
     * 清理过期报告缓存
     */
    int cleanExpiredCache();

    /**
     * 清理过期导出临时文件
     */
    int cleanExpiredExports();

    /**
     * 清理租户全部文件（管理员）
     */
    int deleteTenantFiles(String tenantId);
}
