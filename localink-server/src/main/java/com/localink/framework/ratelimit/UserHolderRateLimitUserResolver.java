package com.localink.framework.ratelimit;

import com.localink.api.dto.UserDTO;
import com.localink.framework.holder.UserHolder;
import com.localink.ratelimit.user.RateLimitUserResolver;
import org.springframework.stereotype.Component;

/**
 * 用户维度解析器：登录校验拦截器（M1.5）写入的 UserHolder 即当前请求身份。
 * 短信/登录等匿名接口无会话时返回 null，该维度跳过（IP 维度兜底）。
 */
@Component
public class UserHolderRateLimitUserResolver implements RateLimitUserResolver {

    @Override
    public String resolveUser() {
        UserDTO user = UserHolder.get();
        return user == null ? null : String.valueOf(user.getId());
    }
}
