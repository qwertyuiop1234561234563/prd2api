package com.dbappsecurity.prd2api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dbappsecurity.prd2api.entity.FileMeta;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文件元信息 Mapper
 */
@Mapper
public interface FileMetaMapper extends BaseMapper<FileMeta> {

    /**
     * 按租户 + md5 查询（去重）
     */
    FileMeta selectByTenantAndMd5(@Param("tenantId") String tenantId, @Param("fileMd5") String fileMd5);

    /**
     * 按租户 + md5 查询任意状态记录（含已软删除），用于处理唯一索引冲突
     */
    FileMeta selectAnyByTenantAndMd5(@Param("tenantId") String tenantId, @Param("fileMd5") String fileMd5);

    /**
     * 复活已软删除的文件记录并指向本次写入的副本
     *
     * @return 受影响行数，0 表示记录已被并发复活或并非删除状态
     */
    int reviveFileMeta(@Param("fileMetaId") String fileMetaId,
                       @Param("fileName") String fileName,
                       @Param("fileSuffix") String fileSuffix,
                       @Param("fileStorePath") String fileStorePath,
                       @Param("fileSize") Long fileSize);

    /**
     * 查询过期文件
     */
    List<FileMeta> selectExpired(@Param("expireBefore") LocalDateTime expireBefore);

    /**
     * 查询租户下全部文件（管理员清理）
     */
    List<FileMeta> selectByTenantId(@Param("tenantId") String tenantId);
}
