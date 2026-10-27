package com.dbappsecurity.prd2api.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：鉴权拦截器、跨域
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final Prd2ApiProperties properties;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                // 下载接口使用一次性临时凭证鉴权，不依赖 Header
                .excludePathPatterns("/api/task/export/download");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = properties.getSecurity().getAllowedOrigins().isEmpty()
                ? new String[]{"*"}
                : properties.getSecurity().getAllowedOrigins().toArray(new String[0]);
        registry.addMapping("/api/**")
                .allowedOriginPatterns(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Trace-Id")
                .maxAge(3600);
    }
}
