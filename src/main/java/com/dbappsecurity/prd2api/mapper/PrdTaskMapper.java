package com.dbappsecurity.prd2api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dbappsecurity.prd2api.entity.PrdTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 任务 Mapper
 */
@Mapper
public interface PrdTaskMapper extends BaseMapper<PrdTask> {

    /**
     * 统计租户下执行中的任务数（并发配额校验）
     */
    int countRunningByTenant(@Param("tenantId") String tenantId);

    /**
     * 同一文件的历史最大版本号
     */
    Integer selectMaxVersionNo(@Param("tenantId") String tenantId, @Param("fileMetaId") String fileMetaId);
}
