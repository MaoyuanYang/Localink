package com.localink.framework.auth;

import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.framework.holder.UserHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 管理端点权限闸门（A-7）：LoginInterceptor 只解决"是否登录"，本拦截器补"是否管理员"。
 * 白名单为配置的手机号集合；未登录到达此处时交由 LoginInterceptor 已拒（order 1 在前），
 * 双保险再判一次匿名直接拒。enabled=false（默认）时全放行——演示单机口径，生产必须开启。
 */
@Slf4j
@Component
public class AdminGuardInterceptor implements HandlerInterceptor {

    private final boolean enabled;
    private final Set<String> adminPhones;

    public AdminGuardInterceptor(
            @Value("${localink.security.admin-guard.enabled:false}") boolean enabled,
            @Value("${localink.security.admin-phones:}") String adminPhones) {
        this.enabled = enabled;
        this.adminPhones = Arrays.stream(adminPhones.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!enabled || !(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        if (!AnnotatedElementUtils.hasAnnotation(handlerMethod.getMethod(), AdminOnly.class)) {
            return true;
        }
        var user = UserHolder.get();
        if (user == null) {
            throw new LocalinkException(BaseCode.UNAUTHORIZED);
        }
        if (!adminPhones.contains(user.getPhone())) {
            log.warn("管理端点越权拒绝[告警]: userId={}, phone={}, uri={}",
                    user.getId(), user.getPhone(), request.getRequestURI());
            throw new LocalinkException(BaseCode.FORBIDDEN, "管理操作需管理员账号");
        }
        return true;
    }
}
