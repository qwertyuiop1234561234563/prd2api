package com.dbappsecurity.prd2api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SpringDoc OpenAPI3 配置
 */
@Configuration
public class OpenApiConfig {

    private static final String SCHEME_TENANT = "tenantHeader";
    private static final String SCHEME_USER = "userHeader";
    private static final String SCHEME_ROLE = "roleHeader";

    @Bean
    public OpenAPI prd2apiOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Prd2Api 企业级 PRD 智能解析中台")
                        .version("1.0.0")
                        .description("""
                                上传 PRD / 设计文档 -> RAG 检索 + 4 阶段串行 Agent 流水线 ->
                                输出业务梳理、待确认需求清单、MySQL DDL、REST 接口契约、研发细分任务清单。
                                认证方式：网关注入 X-Tenant-Id / X-User-Key / X-User-Role。
                                """)
                        .contact(new Contact().name("中台研发组")))
                .components(new Components()
                        .addSecuritySchemes(SCHEME_TENANT, headerScheme("租户ID"))
                        .addSecuritySchemes(SCHEME_USER, headerScheme("用户标识"))
                        .addSecuritySchemes(SCHEME_ROLE, headerScheme("角色 USER/ADMIN")))
                .addSecurityItem(new SecurityRequirement()
                        .addList(SCHEME_TENANT)
                        .addList(SCHEME_USER)
                        .addList(SCHEME_ROLE));
    }

    private SecurityScheme headerScheme(String description) {
        return new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER)
                .description(description);
    }
}
