package com.localink.framework.auth;

import com.localink.api.dto.UserDTO;
import com.localink.constant.UserConstants;
import com.localink.framework.holder.UserHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class TokenRefreshInterceptor implements HandlerInterceptor {

    public static final String AUTH_HEADER = "Authorization";

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = request.getHeader(AUTH_HEADER);
        if (token == null || token.isBlank()) {
            return true;
        }
        String key = UserConstants.TOKEN_KEY_PREFIX + token;
        Map<Object, Object> fields = stringRedisTemplate.opsForHash().entries(key);
        if (fields.isEmpty()) {
            return true;
        }
        UserHolder.set(toUserDTO(fields));
        stringRedisTemplate.expire(key, Duration.ofSeconds(UserConstants.SESSION_TTL_SECONDS));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserHolder.clear();
    }

    private UserDTO toUserDTO(Map<Object, Object> fields) {
        UserDTO user = new UserDTO();
        user.setId(Long.valueOf((String) fields.get(UserConstants.FIELD_ID)));
        user.setPhone((String) fields.get(UserConstants.FIELD_PHONE));
        user.setNickName((String) fields.get(UserConstants.FIELD_NICK_NAME));
        user.setIcon((String) fields.get(UserConstants.FIELD_ICON));
        user.setLevel(Integer.valueOf((String) fields.get(UserConstants.FIELD_LEVEL)));
        return user;
    }
}
