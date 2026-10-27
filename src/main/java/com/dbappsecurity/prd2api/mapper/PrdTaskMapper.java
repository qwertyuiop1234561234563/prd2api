package com.dbappsecurity.prd2api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dbappsecurity.prd2api.entity.PrdTask;
import com.dbappsecurity.prd2api.vo.TaskDailyStatVO;
import com.dbappsecurity.prd2api.vo.TaskStatsVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

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

    /**
     * 统计大盘：总览指标，tenantId 为空表示全租户
     */
    TaskStatsVO statOverview(@Param("tenantId") String tenantId);

    /**
     * 统计大盘：按日趋势
     */
    List<TaskDailyStatVO> statDaily(@Param("tenantId") String tenantId,
                                    @Param("startTime") LocalDateTime startTime);
}
