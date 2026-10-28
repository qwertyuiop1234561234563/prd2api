-- Prd2Api 建表脚本（MySQL 8.0）
CREATE DATABASE IF NOT EXISTS `prd2api` DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;
USE `prd2api`;

CREATE TABLE IF NOT EXISTS `prd_task` (
  `task_id` VARCHAR(64) NOT NULL COMMENT '任务唯一ID UUID',
  `tenant_id` VARCHAR(64) NOT NULL COMMENT '租户ID，隔离数据',
  `user_key` VARCHAR(64) NOT NULL COMMENT '用户标识',
  `file_meta_id` VARCHAR(64) NOT NULL COMMENT '文件元数据ID',
  `version_no` INT DEFAULT 1 COMMENT '文档版本号',
  `task_status` VARCHAR(32) NOT NULL COMMENT 'INIT / PROCESS / SUCCESS / FAIL / CANCELED',
  `progress` TINYINT DEFAULT 0 COMMENT '任务进度0~100',
  `result_markdown` LONGTEXT NULL COMMENT '评审报告markdown',
  `error_msg` TEXT NULL COMMENT '失败原因',
  `model_code` VARCHAR(64) DEFAULT 'default' COMMENT '选用LLM模型编码',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `finish_time` DATETIME NULL,
  `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除 0未删1已删',
  PRIMARY KEY (`task_id`),
  UNIQUE KEY `uk_tenant_file_version`(`tenant_id`,`file_meta_id`,`version_no`),
  KEY `idx_tenant_id`(`tenant_id`),
  KEY `idx_user_key`(`user_key`),
  KEY `idx_status`(`task_status`),
  KEY `idx_file_meta`(`file_meta_id`),
  KEY `idx_tenant_status`(`tenant_id`,`task_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT 'PRD解析任务主表';

CREATE TABLE IF NOT EXISTS `file_meta` (
  `file_meta_id` VARCHAR(64) NOT NULL COMMENT '文件元数据主键',
  `tenant_id` VARCHAR(64) NOT NULL COMMENT '租户ID',
  `file_name` VARCHAR(128) NOT NULL COMMENT '原始文件名',
  `file_suffix` VARCHAR(32) NOT NULL COMMENT '文件后缀',
  `file_store_path` VARCHAR(256) NOT NULL COMMENT '存储路径，本地/OSS',
  `file_md5` VARCHAR(32) NOT NULL COMMENT '文件md5，用于去重',
  `file_size` BIGINT NOT NULL COMMENT '文件大小字节',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `deleted` TINYINT DEFAULT 0,
  PRIMARY KEY (`file_meta_id`),
  UNIQUE KEY `uk_tenant_md5`(`tenant_id`,`file_md5`),
  KEY `idx_tenant_create`(`tenant_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '文件元信息表';

CREATE TABLE IF NOT EXISTS `prd_task_cache` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `tenant_id` VARCHAR(64) NOT NULL,
  `file_md5` VARCHAR(32) NOT NULL,
  `model_code` VARCHAR(64) NOT NULL,
  `chunk_strategy` VARCHAR(32) NOT NULL,
  `cache_result_markdown` LONGTEXT NOT NULL,
  `expire_time` DATETIME NOT NULL,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_cache_key`(`tenant_id`,`file_md5`,`model_code`,`chunk_strategy`),
  KEY `idx_expire_time`(`expire_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '报告缓存表，相同条件直接返回结果';

CREATE TABLE IF NOT EXISTS `tenant_quota` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `tenant_id` VARCHAR(64) NOT NULL UNIQUE COMMENT '租户ID',
  `daily_upload_limit` INT DEFAULT 50 COMMENT '每日上传次数上限',
  `daily_token_limit` BIGINT DEFAULT 1000000 COMMENT '每日token上限',
  `concurrent_task_limit` INT DEFAULT 5 COMMENT '最大并发任务数',
  `used_upload_count` INT DEFAULT 0 COMMENT '今日已上传次数',
  `used_token_count` BIGINT DEFAULT 0 COMMENT '今日消耗token',
  `reset_time` DATETIME NOT NULL COMMENT '配额重置时间',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '租户配额表';

CREATE TABLE IF NOT EXISTS `sys_user` (
  `user_id` VARCHAR(64) NOT NULL COMMENT '用户ID',
  `tenant_id` VARCHAR(64) NOT NULL COMMENT '所属租户ID',
  `username` VARCHAR(64) NOT NULL COMMENT '登录账号，租户内唯一',
  `password_hash` VARCHAR(128) NOT NULL COMMENT 'BCrypt 密码哈希',
  `display_name` VARCHAR(64) DEFAULT NULL COMMENT '显示名',
  `role` VARCHAR(16) NOT NULL DEFAULT 'USER' COMMENT 'ADMIN / USER',
  `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 正常 / DISABLED 禁用',
  `vip_plan_code` VARCHAR(32) DEFAULT NULL COMMENT '当前生效套餐编码',
  `vip_expire_time` DATETIME DEFAULT NULL COMMENT 'VIP 到期时间',
  `last_login_time` DATETIME DEFAULT NULL COMMENT '最近登录时间',
  `fail_count` INT NOT NULL DEFAULT 0 COMMENT '连续登录失败次数',
  `lock_until` DATETIME DEFAULT NULL COMMENT '登录锁定截止时间',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删1已删',
  PRIMARY KEY (`user_id`),
  UNIQUE KEY `uk_tenant_username`(`tenant_id`,`username`),
  KEY `idx_vip_expire`(`vip_expire_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '系统用户表';

CREATE TABLE IF NOT EXISTS `vip_plan` (
  `plan_code` VARCHAR(32) NOT NULL COMMENT '套餐编码',
  `plan_name` VARCHAR(64) NOT NULL COMMENT '套餐名称',
  `level` INT NOT NULL DEFAULT 0 COMMENT '套餐等级 0免费 1基础 2专业',
  `duration_days` INT NOT NULL DEFAULT 30 COMMENT '单次开通有效天数，0 表示不可开通',
  `daily_upload_limit` INT NOT NULL DEFAULT 50 COMMENT '每日上传次数上限',
  `daily_token_limit` BIGINT NOT NULL DEFAULT 1000000 COMMENT '每日 token 上限',
  `concurrent_task_limit` INT NOT NULL DEFAULT 5 COMMENT '最大并发任务数',
  `max_file_size_mb` INT NOT NULL DEFAULT 20 COMMENT '单文件大小上限 MB',
  `model_tier` VARCHAR(16) NOT NULL DEFAULT 'basic' COMMENT '可用模型档位 basic/standard/pro',
  `price_cent` INT NOT NULL DEFAULT 0 COMMENT '参考价格，单位分',
  `description` VARCHAR(256) DEFAULT NULL COMMENT '套餐说明',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1 上架 0 下架',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`plan_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT 'VIP 套餐表';

CREATE TABLE IF NOT EXISTS `user_vip` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `user_id` VARCHAR(64) NOT NULL COMMENT '用户ID',
  `plan_code` VARCHAR(32) NOT NULL COMMENT '套餐编码',
  `source` VARCHAR(16) NOT NULL COMMENT '开通来源 ADMIN 管理员开通 / REDEEM 兑换码',
  `start_time` DATETIME NOT NULL COMMENT '生效时间',
  `expire_time` DATETIME NOT NULL COMMENT '到期时间',
  `operator` VARCHAR(64) DEFAULT NULL COMMENT '操作人',
  `remark` VARCHAR(256) DEFAULT NULL COMMENT '备注',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY `idx_user_time`(`user_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT 'VIP 开通记录表';

CREATE TABLE IF NOT EXISTS `redeem_code` (
  `id` BIGINT AUTO_INCREMENT COMMENT '主键',
  `code` VARCHAR(32) NOT NULL COMMENT '兑换码',
  `plan_code` VARCHAR(32) NOT NULL COMMENT '可兑换套餐编码',
  `status` VARCHAR(16) NOT NULL DEFAULT 'UNUSED' COMMENT 'UNUSED / USED / EXPIRED / VOID',
  `batch_no` VARCHAR(32) DEFAULT NULL COMMENT '生成批次号',
  `used_by_user` VARCHAR(64) DEFAULT NULL COMMENT '使用人用户ID',
  `used_time` DATETIME DEFAULT NULL COMMENT '使用时间',
  `expire_time` DATETIME DEFAULT NULL COMMENT '兑换码自身过期时间，空表示长期有效',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code`(`code`),
  KEY `idx_batch`(`batch_no`),
  KEY `idx_status`(`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT 'VIP 兑换码表';

CREATE TABLE IF NOT EXISTS `coupon` (
  `coupon_id` BIGINT AUTO_INCREMENT COMMENT '优惠券ID',
  `coupon_name` VARCHAR(64) NOT NULL COMMENT '优惠券名称',
  `plan_code` VARCHAR(32) NOT NULL COMMENT '可开通套餐编码',
  `duration_days` INT NOT NULL DEFAULT 0 COMMENT '开通天数，0 表示沿用套餐天数',
  `total_stock` INT NOT NULL COMMENT '发放总量',
  `per_user_limit` INT NOT NULL DEFAULT 1 COMMENT '每人限领数量',
  `claimed_count` INT NOT NULL DEFAULT 0 COMMENT '已领取数量',
  `valid_days` INT NOT NULL DEFAULT 0 COMMENT '领取后有效天数，0 表示跟随领取截止时间',
  `start_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取开始时间',
  `expire_time` DATETIME DEFAULT NULL COMMENT '领取截止时间，空表示长期有效',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1 领取中 0 已下架',
  `create_by` VARCHAR(64) DEFAULT NULL COMMENT '发放人',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`coupon_id`),
  KEY `idx_status_time`(`status`,`expire_time`),
  KEY `idx_plan_code`(`plan_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '优惠券发放批次表';

CREATE TABLE IF NOT EXISTS `user_coupon` (
  `id` BIGINT AUTO_INCREMENT COMMENT '主键',
  `coupon_id` BIGINT NOT NULL COMMENT '优惠券ID',
  `user_id` VARCHAR(64) NOT NULL COMMENT '领取人用户ID',
  `tenant_id` VARCHAR(64) NOT NULL COMMENT '所属租户ID',
  `claim_no` INT NOT NULL DEFAULT 1 COMMENT '该用户第几次领取，用于组合唯一键',
  `status` VARCHAR(16) NOT NULL DEFAULT 'UNUSED' COMMENT 'UNUSED / USED / EXPIRED',
  `receive_time` DATETIME NOT NULL COMMENT '领取时间',
  `use_time` DATETIME DEFAULT NULL COMMENT '使用时间',
  `expire_time` DATETIME DEFAULT NULL COMMENT '使用截止时间，空表示长期有效',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_coupon_user_claim`(`coupon_id`,`user_id`,`claim_no`),
  KEY `idx_user_status`(`user_id`,`status`),
  KEY `idx_coupon_status`(`coupon_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '用户领取的优惠券表';

CREATE TABLE IF NOT EXISTS `user_daily_usage` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `user_id` VARCHAR(64) NOT NULL COMMENT '用户ID',
  `stat_date` DATE NOT NULL COMMENT '统计日期',
  `upload_count` INT NOT NULL DEFAULT 0 COMMENT '当日上传次数',
  `token_count` BIGINT NOT NULL DEFAULT 0 COMMENT '当日 token 消耗',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_user_date`(`user_id`,`stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '用户每日用量表（VIP 额度）';

CREATE TABLE IF NOT EXISTS `audit_log` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `tenant_id` VARCHAR(64) NOT NULL COMMENT '租户ID',
  `user_key` VARCHAR(64) NOT NULL COMMENT '用户标识',
  `user_role` VARCHAR(32) DEFAULT NULL COMMENT '用户角色',
  `action` VARCHAR(64) NOT NULL COMMENT '操作动作，如 任务创建/文件上传',
  `target` VARCHAR(128) DEFAULT NULL COMMENT '操作对象，通常为 taskId / fileMetaId',
  `http_method` VARCHAR(16) DEFAULT NULL COMMENT 'HTTP 方法',
  `uri` VARCHAR(256) DEFAULT NULL COMMENT '请求路径',
  `client_ip` VARCHAR(64) DEFAULT NULL COMMENT '客户端IP',
  `success` TINYINT DEFAULT 1 COMMENT '1 成功 0 失败',
  `cost_ms` BIGINT DEFAULT 0 COMMENT '耗时毫秒',
  `trace_id` VARCHAR(64) DEFAULT NULL COMMENT '全链路追踪ID',
  `error_msg` VARCHAR(512) DEFAULT NULL COMMENT '失败原因',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  KEY `idx_tenant_time`(`tenant_id`,`create_time`),
  KEY `idx_action`(`action`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '操作审计日志表';

-- VIP 套餐种子数据：FREE 仅用于展示，不可开通（duration_days = 0）
INSERT IGNORE INTO `vip_plan`
(`plan_code`, `plan_name`, `level`, `duration_days`, `daily_upload_limit`, `daily_token_limit`,
 `concurrent_task_limit`, `max_file_size_mb`, `model_tier`, `price_cent`, `description`, `status`)
VALUES
('FREE', '免费版', 0, 0, 20, 300000, 2, 10, 'basic', 0, '未开通 VIP 时按所属租户配额使用', 1),
('BASIC', '基础版', 1, 30, 100, 2000000, 5, 20, 'standard', 1900, '适合个人与小团队的日常评审', 1),
('PRO', '专业版', 2, 30, 500, 10000000, 20, 50, 'pro', 9900, '适合高频评审与多项目并行', 1);
