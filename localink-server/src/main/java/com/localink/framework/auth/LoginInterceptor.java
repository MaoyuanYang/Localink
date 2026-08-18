package com.localink.framework.auth;

import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.framework.holder.UserHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class LoginInterceptor implements HandlerInterceptor {

    private static final String USER_API_PREFIX = "/api/user/";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (UserHolder.get() != null) {
            return true;
        }
        if (request.getRequestURI().startsWith(USER_API_PREFIX)
                || !HttpMethod.GET.matches(request.getMethod())) {
            throw new LocalinkException(BaseCode.UNAUTHORIZED);
        }
        return true;
    }
}
