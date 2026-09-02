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
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

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
        Map<String, String> fields = redisCache.hashes().entries(key);
        if (fields.isEmpty()) {
            return true;
        }
        UserHolder.set(toUserDTO(fields));
        redisCache.expire(key, KeyManage.USER_TOKEN.getTtl());
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
