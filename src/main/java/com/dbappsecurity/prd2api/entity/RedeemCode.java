package com.dbappsecurity.prd2api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * VIP 兑换码
 */
@Data
@TableName("redeem_code")
public class RedeemCode {

    public static final String STATUS_UNUSED = "UNUSED";
    public static final String STATUS_USED = "USED";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_VOID = "VOID";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String code;

    private String planCode;

    private String status;

    private String batchNo;

    private String usedByUser;

    private LocalDateTime usedTime;

    private LocalDateTime expireTime;

    private LocalDateTime createTime;
}
