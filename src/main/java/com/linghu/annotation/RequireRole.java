package com.linghu.annotation;

import java.lang.annotation.*;

/**
 * 角色权限控制注解
 * 使用方式: @RequireRole({0, 1}) 表示消费者和仓主都可以访问
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireRole {
    /**
     * 允许的角色数组：0=消费者, 1=仓主, 2=品牌方
     */
    int[] value();
}
