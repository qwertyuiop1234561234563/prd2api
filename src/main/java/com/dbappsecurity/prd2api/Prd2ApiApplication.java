package com.dbappsecurity.prd2api;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Prd2Api 启动类
 * <p>
 * 企业内 PRD 智能解析中台：RAG + 4 阶段串行 Agent 流水线，输出标准化评审报告。
 */
@EnableAsync
@EnableScheduling
@MapperScan("com.dbappsecurity.prd2api.mapper")
@SpringBootApplication
public class Prd2ApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(Prd2ApiApplication.class, args);
    }
}
