package com.dbappsecurity.prd2api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dbappsecurity.prd2api.entity.PrdTaskCache;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 报告缓存 Mapper
 */
@Mapper
public interface PrdTaskCacheMapper extends BaseMapper<PrdTaskCache> {

    /**
     * 查询未过期缓存
     */
    PrdTaskCache selectValidCache(@Param("tenantId") String tenantId,
                                  @Param("fileMd5") String fileMd5,
                                  @Param("modelCode") String modelCode,
                                  @Param("chunkStrategy") String chunkStrategy,
                                  @Param("now") LocalDateTime now);

    /**
     * 清理过期缓存
     */
    int deleteExpired(@Param("now") LocalDateTime now);
}
