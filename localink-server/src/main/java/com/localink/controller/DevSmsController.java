package com.localink.controller;

import com.localink.cache.KeyBuild;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.common.code.BaseCode;
import com.localink.common.result.Result;
import com.localink.constant.KeyManage;
import com.localink.common.exception.LocalinkException;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * dev-only 验证码回查（W0）：模拟短信不落日志，本地联调/演示由此接口取码。
 * 暴露即等于任意账号可被登录，必须默认不存在——开关关闭时本 Bean 不装配（404），
 * 与 admin-guard 同一安全默认哲学；对外部署保持 false（deploy.md 注明）。
 */
@RestController
@RequestMapping("/api/sms")
@ConditionalOnProperty(name = "localink.dev.sms-code-query.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DevSmsController {

    private final RedisCache redisCache;
    private final KeyBuilder keyBuilder;

    @GetMapping("/code/dev")
    public Result<String> queryCode(@RequestParam String phone) {
        if (phone == null || !phone.matches("^1[3-9]\\d{9}$")) {
            throw new LocalinkException(BaseCode.PARAM_ERROR, "phone 手机号格式不正确");
        }
        KeyBuild key = keyBuilder.build(KeyManage.SMS_CODE, phone);
        String code = redisCache.strings().getString(key);
        if (code == null) {
            throw new LocalinkException(BaseCode.SMS_CODE_EXPIRED);
        }
        return Result.ok(code);
    }
}
