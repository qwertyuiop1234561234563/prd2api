package com.dbappsecurity.prd2api.config;

import lombok.extern.slf4j.Slf4j;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * OkHttp 客户端配置：LLM / Embedding / Chroma 共用连接池
 */
@Slf4j
@Configuration
public class HttpClientConfig {

    @Bean
    public OkHttpClient okHttpClient(Prd2ApiProperties properties) {
        Prd2ApiProperties.Llm llm = properties.getLlm();
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(llm.getConnectTimeout(), TimeUnit.MILLISECONDS)
                .readTimeout(llm.getReadTimeout(), TimeUnit.MILLISECONDS)
                .writeTimeout(llm.getReadTimeout(), TimeUnit.MILLISECONDS)
                .connectionPool(new ConnectionPool(32, 5, TimeUnit.MINUTES))
                .retryOnConnectionFailure(true)
                .build();
        log.info("OkHttpClient 初始化完成 connectTimeout={}ms readTimeout={}ms",
                llm.getConnectTimeout(), llm.getReadTimeout());
        return client;
    }
}
