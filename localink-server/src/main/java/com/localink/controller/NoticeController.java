package com.localink.controller;

import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.common.result.Result;
import com.localink.constant.KeyManage;
import com.localink.framework.holder.UserHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户通知（M5-C）：预通知等群发消息落在用户收件箱（ZSet），本接口按 score 倒序读取最新。
 */
@RestController
@RequestMapping("/api/notice")
@RequiredArgsConstructor
public class NoticeController {

    private final RedisCache redisCache;
    private final KeyBuilder keyBuilder;

    @GetMapping
    public Result<List<String>> myNotices() {
        Long userId = UserHolder.get().getId();
        return Result.ok(List.copyOf(redisCache.zsets().reverseRange(
                keyBuilder.build(KeyManage.USER_NOTICE, userId), 0, 9, String.class)));
    }
}
