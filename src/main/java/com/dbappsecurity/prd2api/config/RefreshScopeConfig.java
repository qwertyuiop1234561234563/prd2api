package com.dbappsecurity.prd2api.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * 配置热加载说明
 * <p>
 * Prd2ApiProperties 标注了 @RefreshScope，配置变更后可通过 /actuator/refresh 触发刷新；
 * 生产接入 Nacos / Apollo 后无需改动业务代码，只需替换配置源。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RefreshScopeConfig {

    private final Prd2ApiProperties properties;

    @PostConstruct
    public void init() {
        log.info("配置热加载已启用，存储模式={} 分块策略={} RAG开关={}",
                properties.getStorage().getMode(),
                properties.getChunk().getStrategy(),
                properties.getRag().isEnable());
    }
}
