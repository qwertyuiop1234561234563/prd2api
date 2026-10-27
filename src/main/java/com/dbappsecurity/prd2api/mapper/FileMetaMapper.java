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
     * 查询过期文件
     */
    List<FileMeta> selectExpired(@Param("expireBefore") LocalDateTime expireBefore);

    /**
     * 查询租户下全部文件（管理员清理）
     */
    List<FileMeta> selectByTenantId(@Param("tenantId") String tenantId);
}
