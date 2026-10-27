package com.dbappsecurity.prd2api.config;

import cn.hutool.core.util.StrUtil;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 配置：单机模式，生产可切换哨兵/集群
 */
@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(@Value("${spring.data.redis.host:127.0.0.1}") String host,
                                         @Value("${spring.data.redis.port:6379}") int port,
                                         @Value("${spring.data.redis.password:}") String password,
                                         @Value("${spring.data.redis.database:0}") int database) {
        Config config = new Config();
        SingleServerConfig server = config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database)
                .setConnectionMinimumIdleSize(4)
                .setConnectionPoolSize(16)
                .setTimeout(3000)
                .setRetryAttempts(3);
        if (StrUtil.isNotBlank(password)) {
            server.setPassword(password);
        }
        return Redisson.create(config);
    }
}
