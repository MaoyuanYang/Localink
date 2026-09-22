package com.localink.ratelimit.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 当前请求的客户端 IP 提取：X-Forwarded-For 首段（经代理链）→ X-Real-IP → remoteAddr。
 * 非请求线程调用返回占位符（AOP 可挂非 Web 方法，此时 IP 维度退化为全局共享桶）。
 * 注意：XFF 可伪造，生产环境应在网关/负载层覆盖该头，应用取值只信任入口注入的版本。
 */
public final class RateLimitIpExtractor {

    private static final String NO_CONTEXT = "no-context";

    private RateLimitIpExtractor() {
    }

    public static String currentIp() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return NO_CONTEXT;
        }
        HttpServletRequest request = attributes.getRequest();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        String remoteAddr = request.getRemoteAddr();
        return remoteAddr != null ? remoteAddr : NO_CONTEXT;
    }
}
