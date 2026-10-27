-- Prd2Api 建表脚本（MySQL 8.0）
CREATE DATABASE IF NOT EXISTS `prd2api` DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;
USE `prd2api`;

CREATE TABLE IF NOT EXISTS `prd_task` (
  `task_id` VARCHAR(64) NOT NULL COMMENT '任务唯一ID UUID',
  `tenant_id` VARCHAR(64) NOT NULL COMMENT '租户ID，隔离数据',
  `user_key` VARCHAR(64) NOT NULL COMMENT '用户标识',
  `file_meta_id` VARCHAR(64) NOT NULL COMMENT '文件元数据ID',
  `version_no` INT DEFAULT 1 COMMENT '文档版本号',
  `task_status` VARCHAR(32) NOT NULL COMMENT 'INIT / PROCESS / SUCCESS / FAIL',
  `progress` TINYINT DEFAULT 0 COMMENT '任务进度0~100',
  `result_markdown` LONGTEXT NULL COMMENT '评审报告markdown',
  `error_msg` TEXT NULL COMMENT '失败原因',
  `model_code` VARCHAR(64) DEFAULT 'default' COMMENT '选用LLM模型编码',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `finish_time` DATETIME NULL,
  `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除 0未删1已删',
  PRIMARY KEY (`task_id`),
  KEY `idx_tenant_id`(`tenant_id`),
  KEY `idx_user_key`(`user_key`),
  KEY `idx_status`(`task_status`),
  KEY `idx_file_meta`(`file_meta_id`)
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
  KEY `idx_tenant_md5`(`tenant_id`,`file_md5`)
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
  KEY `idx_cache_key`(`tenant_id`,`file_md5`,`model_code`,`chunk_strategy`)
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
