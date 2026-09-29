package com.localink.framework.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 管理端点标记（A-7）：开启 admin-guard 后，标注端点要求当前登录用户手机号命中
 * localink.security.admin-phones 白名单，否则 40003。默认关闭（演示单机口径全放行），
 * 生产/对外部署应置 enabled=true 并配置白名单。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AdminOnly {
}
