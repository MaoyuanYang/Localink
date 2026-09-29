package com.localink.service;

public interface UserService {

    String login(String phone, String code);

    /**
     * 登出（B-4）：删除 Redis 会话，token 立即失效（此前只有闲置 30 分钟自然过期一条路）。
     */
    void logout(String token);
}
