package com.localink.framework.auth;

import com.localink.api.dto.UserDTO;
import com.localink.cache.KeyBuild;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.constant.KeyManage;
import com.localink.constant.UserConstants;
import com.localink.framework.holder.UserHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class TokenRefreshInterceptor implements HandlerInterceptor {

    public static final String AUTH_HEADER = "Authorization";

    private final RedisCache redisCache;
    private final KeyBuilder keyBuilder;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = request.getHeader(AUTH_HEADER);
        if (token == null || token.isBlank()) {
            return true;
        }
        KeyBuild key = keyBuilder.build(KeyManage.USER_TOKEN, token);
        Map<String, String> fields;
        try {
            fields = redisCache.hashes().entries(key);
        } catch (Exception e) {
            // Redis 不可用：降级为匿名放行（fail-closed 而非 500）——受保护端点由
            // LoginInterceptor 以 40002 拒绝，公开 GET 仍可走 L1 缓存兜底
            log.error("会话 Redis 读取失败, 本次请求按匿名处理, uri={}", request.getRequestURI(), e);
            return true;
        }
        if (fields.isEmpty()) {
            return true;
        }
        try {
            UserHolder.set(toUserDTO(fields));
            redisCache.expire(key, KeyManage.USER_TOKEN.getTtl());
        } catch (Exception e) {
            // 会话字段损坏或续期失败：按匿名处理，不放大为 500
            log.warn("会话解析/续期失败, 本次请求按匿名处理, uri={}", request.getRequestURI(), e);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserHolder.clear();
    }

    private UserDTO toUserDTO(Map<String, String> fields) {
        UserDTO user = new UserDTO();
        user.setId(Long.valueOf(fields.get(UserConstants.FIELD_ID)));
        user.setPhone(fields.get(UserConstants.FIELD_PHONE));
        user.setNickName(fields.get(UserConstants.FIELD_NICK_NAME));
        user.setIcon(fields.get(UserConstants.FIELD_ICON));
        user.setLevel(Integer.valueOf(fields.get(UserConstants.FIELD_LEVEL)));
        return user;
    }
}
