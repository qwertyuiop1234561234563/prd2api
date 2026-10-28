package com.dbappsecurity.prd2api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dbappsecurity.prd2api.entity.UserDailyUsage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;

/**
 * 用户每日用量 Mapper（原子计数，防并发超卖）
 */
@Mapper
public interface UserDailyUsageMapper extends BaseMapper<UserDailyUsage> {

    /**
     * 保证当日用量行存在（唯一索引 + 忽略重复）
     */
    int ensureRow(@Param("userId") String userId, @Param("statDate") LocalDate statDate);

    /**
     * 原子占用一次上传额度，返回 0 表示已超限
     */
    int increaseUploadUsed(@Param("userId") String userId,
                           @Param("statDate") LocalDate statDate,
                           @Param("limit") int limit);

    /**
     * 原子累计 token 消耗，返回 0 表示已超限
     */
    int increaseTokenUsed(@Param("userId") String userId,
                          @Param("statDate") LocalDate statDate,
                          @Param("tokens") long tokens,
                          @Param("limit") long limit);
}
