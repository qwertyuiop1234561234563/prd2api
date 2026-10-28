package com.dbappsecurity.prd2api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dbappsecurity.prd2api.entity.UserVip;
import org.apache.ibatis.annotations.Mapper;

/**
 * VIP 开通记录 Mapper
 */
@Mapper
public interface UserVipMapper extends BaseMapper<UserVip> {
}
