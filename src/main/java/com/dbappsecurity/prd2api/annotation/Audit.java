package com.dbappsecurity.prd2api.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作审计注解
 * <p>
 * 标注在 Controller 方法上，由 AuditLogAspect 统一记录租户、用户、目标、耗时与结果。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audit {

    /**
     * 操作动作描述，如「任务创建」
     */
    String value();
}
