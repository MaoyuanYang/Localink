package com.localink.framework.auth;

import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.framework.holder.UserHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (UserHolder.get() == null) {
            throw new LocalinkException(BaseCode.UNAUTHORIZED);
        }
        return true;
    }
}
